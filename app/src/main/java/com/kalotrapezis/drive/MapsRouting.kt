package com.kalotrapezis.drive

import com.graphhopper.GHRequest
import com.graphhopper.GraphHopper
import com.graphhopper.GraphHopperConfig
import com.graphhopper.config.Profile
import com.graphhopper.jackson.Jackson
import com.graphhopper.routing.WeightingFactory
import com.graphhopper.routing.ev.BooleanEncodedValue
import com.graphhopper.routing.ev.DecimalEncodedValue
import com.graphhopper.routing.ev.IntEncodedValue
import com.graphhopper.routing.ev.Orientation
import com.graphhopper.routing.ev.MaxSpeed
import com.graphhopper.routing.ev.RoadClass
import com.graphhopper.routing.ev.RoadClassLink
import com.graphhopper.routing.ev.TurnRestriction
import com.graphhopper.routing.weighting.DefaultTurnCostProvider
import com.graphhopper.routing.weighting.TurnCostProvider
import com.graphhopper.routing.weighting.Weighting
import com.graphhopper.routing.weighting.custom.CustomWeighting
import com.graphhopper.util.EdgeIteratorState
import com.graphhopper.util.PMap
import com.graphhopper.util.Parameters
import com.graphhopper.util.Translation
import com.graphhopper.util.TranslationMap
import com.graphhopper.util.TurnCostsConfig
import java.io.File
import java.util.Locale

/** One leg of a route's spoken and shown directions; [at] is the index in [Route.points] where it begins. */
internal data class Step(val text: String, val sign: Int, val metres: Double, val at: Int, val street: String, val ref: String = "",
    val junction: Junction? = null, val lanes: Lanes? = null, val toward: String = "")

/** [roads]: what it mostly runs on — "motorway", "main" or "local" — for the choice between routes. */
internal data class Route(val mode: String, val lats: DoubleArray, val lngs: DoubleArray, val metres: Double, val millis: Long, val steps: List<Step>,
    val roads: String = "")

/**
 * Offline routing on the phone: the graph the computer built (tools/maps-greece.sh, tools/maps-graph.yml) opened read-only
 * from Tetra/.System/Maps/Shared/greece-gh with GraphHopper 10.2. GraphHopper turns a profile's custom model into Java bytecode with Janino
 * at run time, which Android cannot load, so the three custom models are written out here by hand (TetraWeightings) —
 * the same rules as GraphHopper's car.json, foot.json and bike.json, which the graph's shortcuts were made with.
 */
internal object MapsRouting {
    /**
     * A car's times as GraphHopper gives them, at the limits, are short of a real drive (the user, 21 Oct: Άγιος Λουκάς →
     * Χαλκίδα "is 30 min in reality, our map says 25"): a fifth more.
     */
    const val CAR_TIME = 1.2

    /** How far a search that keeps the car's heading may look before the shortcuts take over (on the computer it gives up on Athens–Thessaloniki in 0.8 s). */
    private const val MAX_HEADED_NODES = 300_000

    /** What turning back on the road costs a route (GraphHopper's 300 s had it say "turn back" all the way, 23 Oct). */
    private const val TURN_BACK_SECONDS = 1200

    /**
     * Main roads (the user, 24 Oct: "it always chooses the shorter path … even if that means a road that falls off a cliff"):
     * the car's way, a smaller road counting slower. A request hint on the car profile, so no new graph; the graph's shortcuts
     * are made for car.json, so it searches without them, up to [MAX_MAIN_NODES]. It keeps car.json's pull towards the shorter
     * way ([MAIN_DISTANCE]): without it (5 Nov, by Σχηματάρι) it ran to the motorway through a factory's dirt yard where Google
     * and the user go 6 km straight on (the user: "motorway > main roads > lanes between fields, but straight on is not
     * through the motorway at all costs").
     */
    const val MAIN_ROADS = "tetra.main_roads"
    const val MAIN_DISTANCE = 90.0
    /**
     * No cutting across (the user, 5 Nov, in Ναύπλιο at rush hour: "I went round the block or on to a junction further down,
     * not to cut two streams — it should be a mode"): Main roads, a left or straight on out of a side road over a bigger one
     * counting CROSS_IN_TOWN in a town ([Towns]); outside one, as on Main roads ("not everywhere, in big built-up areas").
     * Its setting is still called no_left.
     */
    const val NO_LEFT = "tetra.no_left"
    // shortcut: past this (a drive across Greece) the shortest way is taken, mostly on motorways anyway; a car_main CH profile in maps-graph.yml (a rebuild) would lift it
    private const val MAX_MAIN_NODES = 500_000

    /** How much slower a road of [c] counts on Main roads: the big ones as they are, a track ten times slower. */
    fun mainPriority(c: com.graphhopper.routing.ev.RoadClass): Double = when (c) {
        com.graphhopper.routing.ev.RoadClass.MOTORWAY, com.graphhopper.routing.ev.RoadClass.TRUNK,
        com.graphhopper.routing.ev.RoadClass.PRIMARY, com.graphhopper.routing.ev.RoadClass.SECONDARY -> 1.0
        com.graphhopper.routing.ev.RoadClass.TERTIARY -> 0.9
        com.graphhopper.routing.ev.RoadClass.RESIDENTIAL, com.graphhopper.routing.ev.RoadClass.LIVING_STREET -> 0.6
        com.graphhopper.routing.ev.RoadClass.UNCLASSIFIED -> 0.4
        com.graphhopper.routing.ev.RoadClass.TRACK -> 0.1
        else -> 0.3
    }

    /** maps-graph.yml's profiles, as Jackson reads them there: GraphHopper compares their hash with the graph's. */
    private val PROFILES = linkedMapOf(
        "car" to """{"name":"car","turn_costs":{"vehicle_types":["motorcar","motor_vehicle"]},"custom_model_files":["car.json"]}""",
        "foot" to """{"name":"foot","custom_model_files":["foot.json"]}""",
        "bike" to """{"name":"bike","custom_model_files":["bike.json"]}""",
        "wheelchair" to """{"name":"wheelchair","custom_model":{"priority":[{"if":"!foot_access || hike_rating >= 1","multiply_by":"0"},""" +
            """{"if":"road_class == STEPS","multiply_by":"0"},{"if":"mtb_rating > 1","multiply_by":"0"},{"else":"","multiply_by":"foot_priority"}],""" +
            """"speed":[{"if":"true","limit_to":"foot_average_speed"},{"if":"true","limit_to":"4"}]}}""",
    )

    /** The profiles [dir]'s graph was built with (properties.txt), in maps-graph.yml's order: an older graph lacks the newer ones. */
    fun profiles(dir: File): List<String> {
        val built = runCatching { File(dir, "properties.txt").readLines().firstOrNull { it.startsWith("profiles=") } }.getOrNull()
            ?.substringAfter("=")?.split(",")?.map { it.substringBefore("|") }.orEmpty()
        return PROFILES.keys.filter { it in built }.ifEmpty { listOf("car", "foot", "bike") }
    }

    /** The graphs open, the last used last: up to three, so a route across borders and the limits on its way open none again. */
    private val hoppers = LinkedHashMap<File, GraphHopper>(4, 0.75f, true)

    /** How many threads read each graph now (a route, a limit): one put out of [hoppers] is closed when its last reader is done. */
    private val readers = HashMap<GraphHopper, Int>()
    private val retired = HashSet<GraphHopper>()

    /** [block] on the graph in [dir], which no other thread closes meanwhile. */
    private inline fun <T> reading(dir: File, block: (GraphHopper) -> T): T {
        val gh = synchronized(this) { open(dir).also { readers[it] = (readers[it] ?: 0) + 1 } }
        try { return block(gh) } finally {
            synchronized(this) { val n = readers.getValue(gh) - 1; if (n > 0) readers[gh] = n else { readers.remove(gh); if (retired.remove(gh)) gh.close() } }
        }
    }

    private val said = HashSet<String>()
    /** A failure written to the log once per cause, not at each fix (the limit is asked every 2 s, and it failed without a word). */
    fun once(what: String, e: Throwable) { if (synchronized(said) { said.add("$what: $e") }) WriterLog.e("Maps: $what", e) }

    /** The graph in [dir], opened once and kept (opening maps the files, it reads nothing much). */
    @Synchronized fun open(dir: File): GraphHopper {
        hoppers[dir]?.let { return it }
        hoppers.entries.firstOrNull()?.takeIf { hoppers.size >= 3 }?.let { (d, old) -> hoppers.remove(d); if (old in readers) retired += old else old.close() }
        try { return load(dir) } catch (e: Throwable) { once("graph ${dir.name} not opened", e); throw e }
    }

    private fun load(dir: File): GraphHopper {
        val names = profiles(dir)
        val json = """{"profiles":[${names.joinToString(",") { PROFILES.getValue(it) }}],"profiles_ch":[${names.joinToString(",") { "{\"profile\":\"$it\"}" }}],"profiles_lm":[]}"""
        val config = Jackson.newObjectMapper().readValue(json, GraphHopperConfig::class.java)
            .putObject("graph.location", dir.absolutePath)
            .putObject("graph.dataaccess.default_type", "MMAP_RO")
            .putObject("import.osm.ignored_highways", "")
        val gh = TetraHopper()
        gh.setAllowWrites(false) // no gh.lock in a synced folder
        gh.init(config)
        check(gh.load()) { "No routing graph in ${dir.name}." }
        hoppers[dir] = gh
        return gh
    }

    /** A route by [mode] (car, foot, bike), its directions in [w]'s language. */
    fun route(dir: File, fromLat: Double, fromLng: Double, toLat: Double, toLng: Double, mode: String, w: Words, heading: Double = Double.NaN,
              main: Boolean = false, noLeft: Boolean = false): Route =
        routes(dir, fromLat, fromLng, toLat, toLng, mode, w, heading, main, noLeft).first()

    private val translations by lazy { TranslationMap().doImport() }
    private val translated = HashMap<String, Translation>()

    /**
     * GraphHopper's turn words in [lang], with Tetra's own over the lines it lacks (resources tetra/graphhopper/<lang>.txt,
     * GraphHopper's format: et, ga, lv and mt whole, a few lines of es, fi, hr, lt, nl, pt, ro, sl); English where neither has one.
     */
    @Synchronized fun translation(lang: String): Translation = translated.getOrPut(lang) {
        val base = translations.getWithFallBack(Locale.forLanguageTag(if (lang == "pt") "pt-PT" else lang)) // Tetra's Portuguese is Portugal's
        val ours = MapsRouting::class.java.getResourceAsStream("/tetra/graphhopper/$lang.txt") ?: return@getOrPut base
        val m = HashMap(base.asMap())
        ours.bufferedReader().useLines { ls -> ls.filter { '=' in it && !it.startsWith("#") }.forEach { m[it.substringBefore('=')] = it.substringAfter('=') } }
        val l = Locale.forLanguageTag(lang)
        object : Translation {
            override fun tr(key: String, vararg params: Any?) = m[key.lowercase()]?.takeIf { it.isNotEmpty() }?.let { String.format(Locale.ROOT, it, *params) } ?: key
            override fun asMap() = m
            override fun getLocale() = l
            override fun getLanguage() = lang
        }
    }

    /**
     * Up to three ways there, the fastest first (the user, 21 Oct, beside Google Maps: "can our map have alternative routs with
     * tolls or without, main roads over roral roads"): GraphHopper's alternatives on the same graph; for a car, none that
     * runs a tenth of its way on tracks.
     */
    fun routes(dir: File, fromLat: Double, fromLng: Double, toLat: Double, toLng: Double, mode: String, w: Words,
               heading: Double = Double.NaN, main: Boolean = false, noLeft: Boolean = false): List<Route> {
        val legs = MapsFiles.legs(dir, fromLat, fromLng, toLat, toLng, w) { g, aLat, aLng, bLat, bLng -> seconds(g, aLat, aLng, bLat, bLng, mode) }
        if (legs.size == 1) return direct(legs[0].dir, fromLat, fromLng, toLat, toLng, mode, w, heading, main, noLeft)
        // across borders: each leg on its own country's roads, driven to the crossing and on from there, as one route
        val t0 = System.nanoTime()
        val parts = legs.mapIndexed { i, l -> direct(l.dir, l.fromLat, l.fromLng, l.toLat, l.toLng, mode, w, if (i == 0) heading else Double.NaN, main, noLeft, alternatives = false).first() }
        WriterLog.i("Maps: a route across ${legs.size - 1} border(s), through ${legs.joinToString(" → ") { it.dir.name }}, its legs in ${(System.nanoTime() - t0) / 1_000_000} ms")
        return listOf(join(parts))
    }

    /** How long from one point to the other in seconds, GraphHopper's (no line, no turns), or null when there is no way: to choose a border crossing. */
    fun seconds(dir: File, fromLat: Double, fromLng: Double, toLat: Double, toLng: Double, mode: String): Double? {
        val mode = if (mode == "wheelchair" && "wheelchair" !in profiles(dir)) "foot" else mode
        val req = GHRequest(fromLat, fromLng, toLat, toLng).setProfile(mode)
        req.hints.putObject(Parameters.Routing.INSTRUCTIONS, false).putObject(Parameters.Routing.CALC_POINTS, false)
        val r = reading(dir) { it.route(req) }
        return if (r.hasErrors()) null else r.best.time / 1000.0
    }

    /** Routes one after the other as one: their lines joined at the shared point, the arrival said only at the end. */
    fun join(parts: List<Route>): Route {
        var offset = 0
        val steps = parts.flatMapIndexed { i, r ->
            (if (i < parts.lastIndex) r.steps.filter { it.sign != com.graphhopper.util.Instruction.FINISH } else r.steps)
                .map { it.copy(at = it.at + offset) }.also { offset += r.lats.size - 1 }
        }
        fun line(of: (Route) -> DoubleArray) = parts.flatMapIndexed { i, r -> of(r).asList().drop(if (i == 0) 0 else 1) }.toDoubleArray()
        return Route(parts[0].mode, line { it.lats }, line { it.lngs }, parts.sumOf { it.metres }, parts.sumOf { it.millis }, steps, parts.maxBy { it.metres }.roads)
    }

    /** [routes] on the one graph [dir]; without [alternatives], the fastest alone (a leg across a border). */
    private fun direct(dir: File, fromLat: Double, fromLng: Double, toLat: Double, toLng: Double, mode: String, w: Words,
                       heading: Double, main: Boolean, noLeft: Boolean = false, alternatives: Boolean = true): List<Route> = reading(dir) { gh ->
        val main = main && mode == "car"
        val tr = translation(w.lang); val locale = tr.locale
        val mode = if (mode == "wheelchair" && "wheelchair" !in profiles(dir)) "foot" else mode // a graph from before Rolling
        val req = GHRequest(fromLat, fromLng, toLat, toLng).setProfile(mode).setLocale(locale).setAlgorithm(if (alternatives) Parameters.Algorithms.ALT_ROUTE else "")
            .setPathDetails(listOf("street_name", "street_ref", "road_environment", "road_class"))
        req.hints.putObject("alternative_route.max_paths", 3)
        if (main) req.hints.putObject(MAIN_ROADS, true).putObject(NO_LEFT, noLeft).putObject(Parameters.CH.DISABLE, true).putObject(Parameters.Routing.MAX_VISITED_NODES, MAX_MAIN_NODES)
        // a moving car goes on the way it faces (the user, 23 Oct: each new route said "turn back" for kilometres): one way,
        // without the graph's shortcuts, which take no heading; where that search grows too big, the shortcuts without it
        if (heading.isFinite()) {
            val ahead = GHRequest(fromLat, fromLng, toLat, toLng).setProfile(mode).setLocale(locale).setHeadings(listOf(heading, Double.NaN))
                .setAlgorithm(Parameters.Algorithms.ASTAR_BI).setPathDetails(req.pathDetails)
            ahead.hints.putObject(Parameters.CH.DISABLE, true).putObject(Parameters.Routing.MAX_VISITED_NODES, MAX_HEADED_NODES)
                .putObject(Parameters.Routing.HEADING_PENALTY, TURN_BACK_SECONDS).putObject(MAIN_ROADS, main).putObject(NO_LEFT, main && noLeft)
            val r = gh.route(ahead)
            if (!r.hasErrors()) return@reading listOf(MapsLanes.on(build(gh, r.best, tr, w, mode), dir.absoluteFile.parentFile, w))
        }
        val t0 = System.nanoTime()
        var rsp = gh.route(req)
        if (rsp.hasErrors()) rsp = gh.route(req.setAlgorithm(""))
        if (rsp.hasErrors() && main) { // too far to search without the shortcuts: today's way
            WriterLog.i("Maps: main roads too far to search (${rsp.errors.firstOrNull()?.message}), the shortest way instead")
            return direct(dir, fromLat, fromLng, toLat, toLng, mode, w, heading, false, alternatives = alternatives)
        }
        val t1 = System.nanoTime() // alternatives not to be had here: the one way
        check(!rsp.hasErrors()) { words(rsp.errors.firstOrNull(), w) }
        val all = rsp.all.map { MapsLanes.on(build(gh, it, tr, w, mode), dir.absoluteFile.parentFile, w) }
        WriterLog.i("Maps: route found in ${(t1 - t0) / 1_000_000} ms${if (main) " on main roads" else ""}, its crossings and lanes in ${(System.nanoTime() - t1) / 1_000_000} ms")
        val usable = all.filter { r -> mode != "car" || r.roads != "track" }.ifEmpty { all }
        usable.sortedBy { it.millis }
    }

    /** GraphHopper's error in the user's words (it said "Cannot find point 0: 37.9,23.55" for a spot at sea). */
    fun words(e: Throwable?, w: Words): String {
        val m = e?.message.orEmpty()
        return when {
            e is com.graphhopper.util.exceptions.PointNotFoundException || "Cannot find point" in m ->
                if ((e as? com.graphhopper.util.exceptions.PointNotFoundException)?.pointIndex == 0 || "point 0" in m) w.s(R.string.maps_error_not_near_you)
                else w.s(R.string.maps_error_destination_not_near)
            "Connection between locations not found" in m -> w.s(R.string.maps_error_no_way)
            else -> m.ifEmpty { w.s(R.string.maps_error_no_route) }
        }
    }

    private fun build(gh: GraphHopper, best: com.graphhopper.ResponsePath, tr: Translation, w: Words, mode: String): Route {
        val names = best.pathDetails["street_name"].orEmpty(); val refs = best.pathDetails["street_ref"].orEmpty()
        val environment = best.pathDetails["road_environment"].orEmpty()
        /** Whether a leg is mostly a ferry (GraphHopper 10 has no ferry instruction: it said "turn left" for 58 km of sea). */
        fun ferry(from: Int, to: Int) = environment.filter { it.value?.toString().equals("ferry", true) }
            .sumOf { maxOf(0, minOf(it.last, to) - maxOf(it.first, from)) } * 2 > (to - from)
        /** The road a leg [from]..[to] (point indexes) mostly runs on: its code and name, as "Α8 Ολυμπία Οδός". */
        fun mostly(from: Int, to: Int): String {
            fun longest(ds: List<com.graphhopper.util.details.PathDetail>) = ds.filter { it.last > from && it.first < to && (it.value as? String).orEmpty().isNotBlank() }
                .maxByOrNull { minOf(it.last, to) - maxOf(it.first, from) }?.value as? String
            return listOfNotNull(longest(refs)?.substringBefore(';'), longest(names)).distinct().joinToString(" ")
        }
        var at = 0
        val steps = best.instructions.map { ins ->
            var text = ins.getTurnDescription(tr)
            var street = ins.name.orEmpty(); var ref = (ins.extraInfoJSON["street_ref"] as? String).orEmpty()
            var toward = (ins.extraInfoJSON["street_destination"] as? String).orEmpty().ifEmpty { (ins.extraInfoJSON["street_destination_ref"] as? String).orEmpty() }
            if (ferry(at, at + ins.points.size())) {
                text = w.s(R.string.maps_take_ferry) + (if (street.isNotEmpty()) " ($street)" else "")
                street = w.s(R.string.maps_ferry)
            }
            // a turn onto a ramp with no name says where the ramp leads (the user, 21 Oct, on motorways: "Στρίψτε αριστερά" for 61 km)
            if (street.isEmpty() && ins.sign != com.graphhopper.util.Instruction.FINISH) {
                val road = mostly(at, at + ins.points.size())
                if (road.isNotEmpty() && ins.distance > 1000) {
                    text = w.s(R.string.maps_towards, text, road); toward = road
                    street = road.substringAfter(' ', road); ref = ref.ifEmpty { road.substringBefore(' ').takeIf { r -> r.any(Char::isDigit) }.orEmpty() }
                }
            }
            // a car's turn: its crossing, for the turn card's picture (the user, 23 Oct: Δράμα's five streets)
            val cross = if (mode == "car" && at > 0 && ins.sign !in setOf(0, 4, 5)) runCatching { junction(gh, best.points, at, at + ins.points.size(), ins.sign == 6,
                (ins as? com.graphhopper.util.RoundaboutInstruction)?.exitNumber ?: 0, when (ins.sign) { -8, -98 -> -1; 8 -> 1; else -> 0 }) }.getOrNull() else null
            Step(text.replaceFirstChar { it.uppercase() }, ins.sign, ins.distance, at, street, ref, cross, toward = toward).also { at += ins.points.size() }
        }
        val pts = best.points
        // what it runs on, by share of its length (a motorway has few points for many kilometres)
        val share = HashMap<String, Double>()
        best.pathDetails["road_class"].orEmpty().forEach { d ->
            var m = 0.0
            for (i in d.first until minOf(d.last, pts.size() - 1)) m += metresBetween(pts.getLat(i), pts.getLon(i), pts.getLat(i + 1), pts.getLon(i + 1))
            share.merge(d.value?.toString()?.lowercase().orEmpty(), m, Double::plus)
        }
        val total = share.values.sum().coerceAtLeast(1.0)
        fun part(vararg k: String) = (k.sumOf { share[it] ?: 0.0 } * 100 / total).toInt()
        val roads = when {
            part("track") >= 10 -> "track"
            part("motorway", "trunk") >= 25 -> "motorway"
            part("motorway", "trunk", "primary", "secondary") >= 50 -> "main"
            else -> "local"
        }
        val millis = if (mode == "car") (best.time * CAR_TIME).toLong() else best.time
        return Route(mode, DoubleArray(pts.size()) { pts.getLat(it) }, DoubleArray(pts.size()) { pts.getLon(it) }, best.distance, millis, steps, roads)
    }

    /**
     * The crossing where a route's turn is, at point [at] of [pts] (the next turn's at [next]), as the car arrives: the car
     * roads leaving it from the graph, each one's direction ~20 m out; a [roundabout]'s from its ring's middle, with the
     * [exit] GraphHopper says; a [uTurn] (−1 left, 1 right) back along the way in. Null where the turn is not at a crossing
     * the graph has.
     */
    private fun junction(gh: GraphHopper, pts: com.graphhopper.util.PointList, at: Int, next: Int, roundabout: Boolean, exit: Int, uTurn: Int): Junction? {
        val na = gh.baseGraph.nodeAccess; val access = gh.encodingManager.getBooleanEncodedValue("car_access")
        if (at < 1) return null
        val snap = gh.locationIndex.findClosest(pts.getLat(at), pts.getLon(at)) { it.get(access) || it.getReverse(access) }
        if (!snap.isValid) return null
        val node = if (snap.snappedPosition == com.graphhopper.storage.index.Snap.Position.TOWER) snap.closestNode
            else listOf(snap.closestEdge.baseNode, snap.closestEdge.adjNode).minBy { metresBetween(na.getLat(it), na.getLon(it), pts.getLat(at), pts.getLon(at)) }
        if (metresBetween(na.getLat(node), na.getLon(node), pts.getLat(at), pts.getLon(at)) > 15) return null
        return crossing(gh.baseGraph, gh.encodingManager, node, pts, at, next, roundabout, exit, uTurn)
    }

    /** [junction] at the graph's [node]: apart from finding the node, so a test can give it a small graph of its own. */
    internal fun crossing(g: com.graphhopper.storage.BaseGraph, em: com.graphhopper.routing.util.EncodingManager, node: Int,
                          pts: com.graphhopper.util.PointList, at: Int, next: Int, roundabout: Boolean, exit: Int = 0, uTurn: Int = 0): Junction? {
        val na = g.nodeAccess
        val access = em.getBooleanEncodedValue("car_access"); val ring = em.getBooleanEncodedValue("roundabout")
        val kind = if (em.hasEncodedValue(RoadClass.KEY)) em.getEnumEncodedValue(RoadClass.KEY, RoadClass::class.java) else null
        val ramp = if (em.hasEncodedValue(RoadClassLink.KEY)) em.getBooleanEncodedValue(RoadClassLink.KEY) else null
        fun car(e: EdgeIteratorState) = e.get(access) || e.getReverse(access)
        /** A road's size, for the picture's thickness: 2 a main road, 1 secondary or tertiary, 0 a street, −1 a ramp, −2 a service
         *  road or track — a car park's aisle, a driveway, a field's track: not a road the driver counts (the user, 3 Nov). */
        fun size(e: EdgeIteratorState): Int = if (ramp != null && e.get(ramp)) -1 else when (kind?.let { e.get(it) }) {
            RoadClass.MOTORWAY, RoadClass.TRUNK, RoadClass.PRIMARY -> 2
            RoadClass.SECONDARY, RoadClass.TERTIARY -> 1
            null, RoadClass.RESIDENTIAL, RoadClass.UNCLASSIFIED, RoadClass.LIVING_STREET, RoadClass.ROAD -> 0
            else -> -2
        }
        val explorer = g.createEdgeExplorer()
        fun apart(a: Double, b: Double) = Math.abs(((a - b) % 360 + 540) % 360 - 180)
        /** A car road at [node]: its first stretch, its point ~40 m along to draw, whether a car may leave by it ([out]) and
         *  come by it ([into]), its [size] and name; [to] the node at its other end, [length] in metres. */
        class Arm(val node: Int, val to: Int, val length: Double, val first: Double, val lat: Double, val lng: Double,
                  val out: Boolean, val into: Boolean, val size: Int, val name: String)
        fun arms(n: Int, skip: (EdgeIteratorState) -> Boolean): List<Arm> {
            val out = ArrayList<Arm>()
            val it = explorer.setBaseNode(n)
            while (it.next()) {
                if (!car(it) || skip(it)) continue
                val geo = it.fetchWayGeometry(com.graphhopper.util.FetchMode.ALL)
                if (geo.size() < 2) continue
                val first = bearing(geo.getLat(0), geo.getLon(0), geo.getLat(1), geo.getLon(1))
                // ~40 m along, or where the road itself turns 30° from how it leaves (the user, 5 Nov, at Άρμα: out of a loop
                // onto Θήβας - Χαλκίδας, seen 40 m along the loop it pointed back, and the card drew a U-turn for a left turn)
                var k = 1
                while (k < geo.size() - 1 && metresBetween(geo.getLat(0), geo.getLon(0), geo.getLat(k), geo.getLon(k)) < 40 &&
                    apart(bearing(geo.getLat(k), geo.getLon(k), geo.getLat(k + 1), geo.getLon(k + 1)), first) < 30) k++
                // OpenStreetMap at times has two ways between the same two nodes (Δράμα's Καραϊσκάκη at Εθνικής Αμύνης): one road
                if (out.any { a -> a.to == it.adjNode && apart(a.first, first) < 5 }) continue
                out += Arm(n, it.adjNode, it.distance, first, geo.getLat(k), geo.getLon(k), it.get(access), it.getReverse(access), size(it), it.name.orEmpty())
            }
            return out
        }
        /** Whether [n] is a crossing: three roads or more meet there, not counting service roads and tracks. */
        fun crossingAt(n: Int) = arms(n) { false }.count { it.size > -2 } >= 3
        /** The way in, the way on, then each other road once (service roads and tracks not: only when the route takes them).
         *  Two arms within 30° are one road where both have its name, or within 20° where neither has a name, one only comes to
         *  the crossing and the other only leaves it: a dual carriageway's halves. A fork's two ways out, however close, are two;
         *  two one-way streets of their own names (Σταδίου and Πανεπιστημίου at Ομόνοια) too. */
        fun same(d: List<Double>, arms: List<Arm>, i: Int, j: Int) = (arms[i].name.isNotEmpty() && arms[i].name == arms[j].name && apart(d[i], d[j]) < 30) ||
            (arms[i].name == arms[j].name && apart(d[i], d[j]) < 20 && ((arms[i].out && !arms[i].into && arms[j].into && !arms[j].out) ||
                (arms[i].into && !arms[i].out && arms[j].out && !arms[j].into)))
        fun oneEach(d: List<Double>, arms: List<Arm>, inArm: Int, outArm: Int): List<Int> {
            val keep = arrayListOf(inArm, outArm)
            for (i in d.indices) if (i !in keep && arms[i].size > -2 && keep.none { same(d, arms, i, it) }) keep += i
            return keep
        }
        fun turned(b: Double, ref: Double) = ((b - ref) % 360 + 540) % 360 - 180
        val dirs: List<Double>; val sizes: List<Int>
        var then = emptyList<Double>(); var thenSizes = emptyList<Int>(); var thenTaken = -1; var entries = emptySet<Int>()
        var path = emptyList<Pair<Double, Double>>()
        // the crossing's nodes: this one and those a short link away that are crossings too — OpenStreetMap often draws one big
        // crossing as two or three small ones a few metres apart (Νικήσιανη's 5–6 streets: no node there has five roads). A link
        // of 12 m at most, or 20 m where it is one-way (a dual carriageway's side); never a bend or one along a road to the next
        // crossing 20 m on (the user, 3 Nov: a sharp left at three roads drawn with five)
        val group = LinkedHashSet<Int>()
        if (!roundabout) {
            val todo = ArrayDeque(listOf(node)); val links = g.createEdgeExplorer() // its own: crossingAt walks the other
            while (todo.isNotEmpty() && group.size < 8) {
                val n = todo.removeFirst(); if (!group.add(n)) continue
                val it = links.setBaseNode(n)
                while (it.next()) if (car(it) && size(it) > -2 && it.adjNode !in group &&
                    it.distance <= (if (it.get(access) != it.getReverse(access)) 20.0 else 12.0) &&
                    metresBetween(na.getLat(it.adjNode), na.getLon(it.adjNode), na.getLat(node), na.getLon(node)) <= 25 && crossingAt(it.adjNode)) todo += it.adjNode
            }
        }
        /** The roads at the route's own node: the way in, the way on, the rest; and where the route turns again at a crossing
         *  a short road on (a staggered crossing), that one's roads too. */
        fun one(): List<Pair<Double, Int>>? {
            // the way in and the way on are the roads the route itself runs along: its points next to the crossing lie on them
            val nLat = na.getLat(node); val nLng = na.getLon(node)
            val all = arms(node) { false }
            if (at + 1 >= pts.size()) return null
            val back = bearing(nLat, nLng, pts.getLat(at - 1), pts.getLon(at - 1)); val on = bearing(nLat, nLng, pts.getLat(at + 1), pts.getLon(at + 1))
            val inArm = all.indices.minByOrNull { apart(all[it].first, back) } ?: return null
            val outArm = all.indices.minBy { apart(all[it].first, on) }
            val ref = bearing(nLat, nLng, all[inArm].lat, all[inArm].lng) + 180 // the way in straight down
            val d = all.map { turned(bearing(nLat, nLng, it.lat, it.lng), ref) }
            // the route's own shape for 50 m on, in metres right and ahead of the crossing, the way in from below
            path = ArrayList<Pair<Double, Double>>().apply {
                var len = 0.0; var k = at
                while (k + 1 < pts.size() && len < 50) {
                    len += metresBetween(pts.getLat(k), pts.getLon(k), pts.getLat(k + 1), pts.getLon(k + 1)); k++
                    val m = metresBetween(nLat, nLng, pts.getLat(k), pts.getLon(k)); val a = Math.toRadians(turned(bearing(nLat, nLng, pts.getLat(k), pts.getLon(k)), ref))
                    add(Math.sin(a) * m to Math.cos(a) * m)
                }
            }
            if (inArm == outArm) { // back the way it came: drawn beside the way in, on the side it turns
                if (uTurn == 0) return null
                return listOf(-180.0 to all[inArm].size, (if (uTurn < 0) -179.0 else 179.0) to all[inArm].size) +
                    oneEach(d, all, inArm, inArm).drop(2).map { d[it] to all[it].size }
            }
            val kept = oneEach(d, all, inArm, outArm)
            if (kept.size < 3) return null
            // a staggered crossing: the way on is a short link to another crossing where the next turn is
            val link = all[outArm]; val b = link.to
            if (link.length <= 30 && next + 1 < pts.size() && b != node && crossingAt(b) &&
                metresBetween(na.getLat(b), na.getLon(b), pts.getLat(next), pts.getLon(next)) < 5) {
                val bLat = na.getLat(b); val bLng = na.getLon(b)
                val there = arms(b) { it.adjNode == node }
                val leave = bearing(bLat, bLng, pts.getLat(next + 1), pts.getLon(next + 1))
                val bOut = there.indices.minByOrNull { apart(there[it].first, leave) }
                if (bOut != null) {
                    val bd = there.map { turned(bearing(bLat, bLng, it.lat, it.lng), ref) }
                    val bKept = oneEach(bd, there, bOut, bOut).drop(1)
                    val order = bKept.sortedBy { bd[it] }
                    then = order.map { bd[it] }; thenSizes = order.map { there[it].size }; thenTaken = order.indexOf(bOut)
                }
            }
            return kept.map { d[it] to all[it].size }
        }
        /** The roads leaving a crossing of several nodes, seen from its middle; the way on is where the route leaves it. */
        fun many(): List<Pair<Double, Int>>? {
            val cLat = group.map { na.getLat(it) }.average(); val cLng = group.map { na.getLon(it) }.average()
            val all = group.flatMap { n -> arms(n) { it.adjNode in group } }
            // the route ~30 m from the middle before and after: which roads it comes in and goes on by
            fun leave(by: Int): Int? { var k = at; while (k + by in 0 until pts.size()) { k += by; if (metresBetween(cLat, cLng, pts.getLat(k), pts.getLon(k)) >= 30) return k }; return null }
            val j = leave(-1) ?: return null; val k = leave(1) ?: return null
            val far = all.map { bearing(cLat, cLng, it.lat, it.lng) }
            val inArm = far.indices.minByOrNull { apart(far[it], bearing(cLat, cLng, pts.getLat(j), pts.getLon(j))) } ?: return null
            val outArm = far.indices.minBy { apart(far[it], bearing(cLat, cLng, pts.getLat(k), pts.getLon(k))) }
            if (inArm == outArm) return null
            val ref = far[inArm] + 180
            val d = far.map { turned(it, ref) }
            return oneEach(d, all, inArm, outArm).map { d[it] to all[it].size }
        }
        if (!roundabout) {
            // the crossing as several nodes only where that makes five roads or more; else the turn's own node, exactly
            val d = (if (group.size > 1) many()?.takeIf { it.size >= 5 } else null) ?: one() ?: return null
            dirs = d.map { it.first }; sizes = d.map { it.second }
        } else { // a roundabout: round the ring from the way in, its middle, the roads leaving it, seen from there
            val nodes = LinkedHashSet<Int>(); val todo = ArrayDeque(listOf(node))
            while (todo.isNotEmpty() && nodes.size < 60) {
                val n = todo.removeFirst(); if (!nodes.add(n)) continue
                val it = explorer.setBaseNode(n); while (it.next()) if (it.get(ring) && it.adjNode !in nodes) todo += it.adjNode
            }
            if (nodes.size < 3 || next + 1 >= pts.size()) return null
            val cLat = nodes.map { na.getLat(it) }.average(); val cLng = nodes.map { na.getLon(it) }.average()
            val all = nodes.flatMap { n -> arms(n) { it.get(ring) } }
            // the way in at the entry node, the way out at the ring node where the next direction starts: the route runs on both
            val exitNode = nodes.minBy { metresBetween(na.getLat(it), na.getLon(it), pts.getLat(next), pts.getLon(next)) }
            fun road(n: Int, k: Int) = all.indices.filter { all[it].node == n }
                .minByOrNull { apart(all[it].first, bearing(na.getLat(n), na.getLon(n), pts.getLat(k), pts.getLon(k))) }
            val inArm = road(node, at - 1) ?: return null; val outArm = road(exitNode, next + 1) ?: return null
            if (inArm == outArm) return null
            val far = all.map { bearing(cLat, cLng, it.lat, it.lng) }
            val ref = far[inArm] + 180
            val d = far.map { turned(it, ref) }
            val kept = oneEach(d, all, inArm, outArm)
            dirs = kept.map { d[it] }; sizes = kept.map { all[it].size }
            // the roads only into the ring (no half of them leaves it): GraphHopper does not count them as exits, so neither may the picture
            entries = kept.indices.drop(2).filter { k -> all.indices.none { all[it].out && (it == kept[k] || same(d, all, it, kept[k])) } }.toSet()
        }
        if (dirs.size < 3 && !(uTurn != 0 && dirs.size == 2)) return null
        val order = dirs.indices.sortedBy { dirs[it] }
        return Junction(order.map { dirs[it] }, order.indexOf(1), roundabout, order.map { sizes[it] } + thenSizes,
            if (roundabout) exit else 0, then, thenTaken, entries.map { order.indexOf(it) }.toSet(), path)
    }

    /**
     * The speed limit of the road nearest [lat],[lng] in km/h, from OSM's maxspeed in the graph (the user, 21 Oct: "ξέρουμε και
     * όρια ταχύτητας; να το βάλουμε να κοκκινίζει όταν το περνάω;"); null where it is not known or no road is near.
     */
    fun limitAt(dir: File, lat: Double, lng: Double): Int? = runCatching {
        if (!File(dir, "properties").isFile) error("no graph in ${dir.name}")
        reading(dir) { gh ->
            val e = car(gh, lat, lng) ?: return@reading null
            val max = gh.encodingManager.getDecimalEncodedValue("max_speed")
            posted(e.get(max), e.getReverse(max))
        }
    }.onFailure { once("no speed limit from ${dir.name}", it) }.getOrNull()

    /** How far the nearest car road of the graph in [dir] is from [lat],[lng], in metres (∞: none near, or no graph): where maps' bounds overlap, whose roads these are. */
    fun roadMetres(dir: File, lat: Double, lng: Double): Double = if (!File(dir, "properties").isFile) Double.POSITIVE_INFINITY else
        runCatching { reading(dir) { snap(it, lat, lng).let { s -> if (s.isValid) s.queryDistance else Double.POSITIVE_INFINITY } } }
            .onFailure { once("roads of ${dir.name} not read", it) }.getOrDefault(Double.POSITIVE_INFINITY)

    private fun snap(gh: GraphHopper, lat: Double, lng: Double): com.graphhopper.storage.index.Snap {
        val access = gh.encodingManager.getBooleanEncodedValue("car_access")
        return gh.locationIndex.findClosest(lat, lng) { e -> e.get(access) || e.getReverse(access) }
    }

    /** The car road nearest [lat],[lng] in [gh], within 40 m. */
    /** The car road of the graph in [dir] nearest [lat],[lng], for the log: its class, name and number, "tertiary, no name" when OSM has none. */
    fun roadAt(dir: File, lat: Double, lng: Double): String = runCatching {
        reading(dir) { gh ->
            val e = car(gh, lat, lng) ?: return@reading "no road"
            val c = e.get(gh.encodingManager.getEnumEncodedValue(RoadClass.KEY, RoadClass::class.java)).toString()
            val name = listOfNotNull(e.getValue("street_ref") as? String, e.name.takeIf { it.isNotEmpty() }).joinToString(" ")
            if (name.isEmpty()) "$c, no name" else "$c «$name»"
        }
    }.getOrDefault("?")

    private fun car(gh: GraphHopper, lat: Double, lng: Double) = snap(gh, lat, lng).let { s -> s.closestEdge.takeIf { s.isValid && s.queryDistance <= 40 } }

    /**
     * The sign's number from the graph's two directions of max_speed: GraphHopper keeps ∞ where OSM has no number
     * ("GR:urban", none tagged) and 150 for "none" or more — neither is a sign, so null, never a guess. It stores in 2 km/h
     * steps (a 25 comes back 26), and signs go by fives, so the nearest five.
     */
    fun posted(vararg kmh: Double): Int? =
        kmh.filter { it.isFinite() && it > 0 && it < MaxSpeed.UNLIMITED_SIGN_SPEED }.minOrNull()?.let { Math.round(it / 5).toInt() * 5 }

    /**
     * A turn's seconds on Main roads by the streams of traffic it cuts (the user, 5 Nov: "out of a side road, left or straight
     * across, I cut two streams; I went round the block to turn left from the main road, with the right of way — one stream"):
     * right into one stream; left off the road with the right of way, across one; left or straight across a bigger road from a
     * smaller one, across two with none — and in a town on No cutting across ([NO_LEFT]), as long as a way round the block.
     * Tuned 5 Nov on drives round Θήβα, Σχηματάρι and Ναύπλιο (Θήβα → Χαλκίδα 25 turns down to 6).
     */
    private const val RIGHT_TURN = 30.0
    private const val LEFT_TURN = 60.0
    private const val CROSS = 120.0
    private const val CROSS_IN_TOWN = 300.0

    /** A road's size at a crossing: the smaller the bigger, residential and unclassified alike. */
    private fun rank(c: RoadClass) = when (c) {
        RoadClass.MOTORWAY -> 0; RoadClass.TRUNK -> 1; RoadClass.PRIMARY -> 2; RoadClass.SECONDARY -> 3; RoadClass.TERTIARY -> 4
        RoadClass.RESIDENTIAL, RoadClass.UNCLASSIFIED, RoadClass.LIVING_STREET -> 5
        else -> 6
    }

    /**
     * Main roads' turns (the user, 5 Nov, beside Google's way: "by eye, the turns — turn here, turn there; the other has far
     * fewer"): [base]'s costs, and the seconds above for the streams a turn cuts. The graph keeps no road's direction (no
     * orientation), so a turn's angle comes from the two roads' first metres; [towns] when No cutting across is on.
     */
    private class Turns(private val base: TurnCostProvider, private val g: com.graphhopper.storage.BaseGraph,
                        private val road: com.graphhopper.routing.ev.EnumEncodedValue<RoadClass>, private val roundabout: BooleanEncodedValue,
                        private val towns: Towns?) : TurnCostProvider {
        private val explorer = g.createEdgeExplorer()
        override fun calcTurnMillis(inEdge: Int, viaNode: Int, outEdge: Int) = base.calcTurnMillis(inEdge, viaNode, outEdge)
        override fun calcTurnWeight(inEdge: Int, viaNode: Int, outEdge: Int): Double {
            val w = base.calcTurnWeight(inEdge, viaNode, outEdge)
            if (w.isInfinite() || inEdge < 0 || outEdge < 0 || inEdge == outEdge) return w
            val other = g.getOtherNode(outEdge, viaNode)
            if (other == viaNode || g.getOtherNode(inEdge, viaNode) == viaNode) return w // a loop
            val ins = g.getEdgeIteratorState(inEdge, viaNode); val outs = g.getEdgeIteratorState(outEdge, other)
            if (ins.get(roundabout) || outs.get(roundabout)) return w // a roundabout gives way by itself
            val inn = ins.fetchWayGeometry(com.graphhopper.util.FetchMode.ALL); val out = outs.fetchWayGeometry(com.graphhopper.util.FetchMode.ALL)
            val n = inn.size()
            val turn = ((bearing(out.getLat(0), out.getLon(0), out.getLat(1), out.getLon(1)) -
                bearing(inn.getLat(n - 2), inn.getLon(n - 2), inn.getLat(n - 1), inn.getLon(n - 1)) + 540) % 360) - 180
            // the roads meeting here: a bend where two pieces of one road meet is no crossing; the biggest road but mine and my way on
            val mine = rank(ins.get(road)); val on = rank(outs.get(road))
            val it = explorer.setBaseNode(viaNode); var roads = 0; var biggest = 7
            while (it.next()) { roads++; if (it.edge != inEdge && it.edge != outEdge) biggest = minOf(biggest, rank(it.get(road))) }
            if (roads <= 2) return w
            val across = biggest < mine && (turn < -40 || (Math.abs(turn) < 40 && on > biggest)) // out of a smaller road, left or straight over
            return w + when {
                turn > 40 -> RIGHT_TURN
                across -> if (towns != null && towns.inside(g.nodeAccess.getLat(viaNode), g.nodeAccess.getLon(viaNode))) CROSS_IN_TOWN else CROSS
                turn < -40 -> LEFT_TURN
                else -> 0.0
            }
        }
    }

    /**
     * The built-up areas of a graph's country for No cutting across: within 4 km of a city, 1.5 km of a town, 1 km of a suburb,
     * from <id>-places.tsv beside the graph (maps-tools/maps-places.py).
     */
    // shortcut: circles round a place's point, not the town's real edge; OSM's landuse=residential areas would be truer (a new file)
    internal class Towns(file: File) {
        private val at: DoubleArray // lat, lng, metres
        init {
            val radius = mapOf("city" to 4000.0, "town" to 1500.0, "suburb" to 1000.0)
            val out = ArrayList<Double>()
            file.forEachLine { line ->
                val p = line.split('\t')
                val r = radius[p[0]] ?: return@forEachLine
                val lat = p.getOrNull(1)?.toDoubleOrNull() ?: return@forEachLine; val lng = p.getOrNull(2)?.toDoubleOrNull() ?: return@forEachLine
                out += lat; out += lng; out += r
            }
            at = out.toDoubleArray()
        }
        fun inside(lat: Double, lng: Double): Boolean {
            for (i in at.indices step 3) if (Math.abs(at[i] - lat) < 0.04 && metresBetween(lat, lng, at[i], at[i + 1]) < at[i + 2]) return true
            return false
        }
    }

    private class TetraHopper : GraphHopper() {
        /** The towns beside this graph, read the first time No cutting across asks; null without the places file. */
        val towns by lazy {
            val dir = File(graphHopperLocation)
            File(dir.parentFile, dir.name.removeSuffix("-gh") + "-places.tsv").takeIf { it.isFile }?.let { f -> runCatching { Towns(f) }.onFailure { once("towns not read", it) }.getOrNull() }
        }
        override fun createWeightingFactory() = WeightingFactory { profile, hints, noTurns -> weighting(profile, hints, noTurns) }

        private fun weighting(profile: Profile, request: PMap, noTurns: Boolean): Weighting {
            val em = encodingManager
            val hints = PMap(profile.hints).putAll(request)
            // as GraphHopper's DefaultWeightingFactory
            val turns = if (profile.hasTurnCosts() && !noTurns) {
                val o = if (em.hasEncodedValue(Orientation.KEY)) em.getDecimalEncodedValue(Orientation.KEY) else null
                val u = hints.getInt(Parameters.Routing.U_TURN_COSTS, profile.turnCostsConfig.uTurnCosts)
                DefaultTurnCostProvider(em.getTurnBooleanEncodedValue(TurnRestriction.key(profile.name)), o, baseGraph.baseGraph,
                    TurnCostsConfig(profile.turnCostsConfig).setUTurnCosts(u))
            } else TurnCostProvider.NO_TURN_COST_PROVIDER
            val heading = hints.getDouble(Parameters.Routing.HEADING_PENALTY, profile.customModel?.headingPenalty ?: Parameters.Routing.DEFAULT_HEADING_PENALTY)
            fun b(n: String) = em.getBooleanEncodedValue(n)
            fun d(n: String) = em.getDecimalEncodedValue(n)
            fun i(n: String) = em.getIntEncodedValue(n)
            fun EdgeIteratorState.v(e: BooleanEncodedValue, r: Boolean) = if (r) getReverse(e) else get(e)
            fun EdgeIteratorState.v(e: DecimalEncodedValue, r: Boolean) = if (r) getReverse(e) else get(e)
            fun EdgeIteratorState.v(e: IntEncodedValue, r: Boolean) = if (r) getReverse(e) else get(e)
            val p = when (profile.name) {
                "car" -> { // car.json, or Main roads ([MAIN_ROADS])
                    val access = b("car_access"); val speed = d("car_average_speed")
                    val road = em.getEnumEncodedValue(com.graphhopper.routing.ev.RoadClass.KEY, com.graphhopper.routing.ev.RoadClass::class.java)
                    if (hints.getBool(MAIN_ROADS, false)) CustomWeighting.Parameters({ e, r -> minOf(999.0, e.v(speed, r)) }, { speed.maxOrMaxStorableDecimal },
                        { e, r -> if (!e.v(access, r)) 0.0 else mainPriority(e.get(road)) }, { 1.0 }, MAIN_DISTANCE, heading)
                    else CustomWeighting.Parameters({ e, r -> minOf(999.0, e.v(speed, r)) }, { speed.maxOrMaxStorableDecimal },
                        { e, r -> if (!e.v(access, r)) 0.0 else 1.0 }, { 1.0 }, 90.0, heading)
                }
                "foot" -> { // foot.json
                    val access = b("foot_access"); val speed = d("foot_average_speed"); val prio = d("foot_priority")
                    val hike = i("hike_rating"); val mtb = i("mtb_rating")
                    CustomWeighting.Parameters({ e, r -> minOf(999.0, e.v(speed, r)) }, { speed.maxOrMaxStorableDecimal },
                        { e, r ->
                            var v = 1.0
                            if (!e.v(access, r) || e.v(hike, r) >= 2) v *= 0.0
                            if (e.v(mtb, r) > 2) v *= 0.0 else v *= e.v(prio, r)
                            v
                        }, { prio.maxOrMaxStorableDecimal }, 0.0, heading)
                }
                "wheelchair" -> { // maps-graph.yml's inline model: foot.json without steps or rough paths, at most 4 km/h
                    val access = b("foot_access"); val speed = d("foot_average_speed"); val prio = d("foot_priority")
                    val hike = i("hike_rating"); val mtb = i("mtb_rating")
                    val road = em.getEnumEncodedValue(com.graphhopper.routing.ev.RoadClass.KEY, com.graphhopper.routing.ev.RoadClass::class.java)
                    CustomWeighting.Parameters({ e, r -> minOf(999.0, e.v(speed, r), 4.0) }, { minOf(speed.maxOrMaxStorableDecimal, 4.0) },
                        { e, r ->
                            var v = 1.0
                            if (!e.v(access, r) || e.v(hike, r) >= 1) v *= 0.0
                            if (e.get(road) == com.graphhopper.routing.ev.RoadClass.STEPS) v *= 0.0
                            if (e.v(mtb, r) > 1) v *= 0.0 else v *= e.v(prio, r)
                            v
                        }, { prio.maxOrMaxStorableDecimal }, 0.0, heading)
                }
                "bike" -> { // bike.json
                    val access = b("bike_access"); val speed = d("bike_average_speed"); val prio = d("bike_priority")
                    val hike = i("hike_rating"); val mtb = i("mtb_rating"); val round = b("roundabout")
                    CustomWeighting.Parameters({ e, r ->
                            var v = minOf(999.0, e.v(speed, r))
                            if (!e.v(access, r) && e.v(access, !r)) v = minOf(v, 5.0)
                            v
                        }, { speed.maxOrMaxStorableDecimal },
                        { e, r ->
                            var v = e.v(prio, r)
                            if (e.v(mtb, r) > 2) v *= 0.0
                            if (e.v(hike, r) > 1) v *= 0.0
                            val fwd = e.v(access, r); val back = e.v(access, !r)
                            if (!fwd && (!back || e.v(round, r))) v *= 0.0 else if (!fwd && back) v *= 0.2
                            v
                        }, { prio.maxOrMaxStorableDecimal }, 0.0, heading)
                }
                else -> throw IllegalArgumentException("No profile ${profile.name}")
            }
            val main = profile.name == "car" && hints.getBool(MAIN_ROADS, false) && turns !== TurnCostProvider.NO_TURN_COST_PROVIDER
            return CustomWeighting(if (main) Turns(turns, baseGraph.baseGraph, em.getEnumEncodedValue(RoadClass.KEY, RoadClass::class.java),
                em.getBooleanEncodedValue("roundabout"), if (hints.getBool(NO_LEFT, false)) towns else null) else turns, p)
        }
    }
}
