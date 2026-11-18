package com.kalotrapezis.drive

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * "Hey Tetra" with no trained model (the user, 15 Nov: "match the voice's signature"): what the mic hears is compared with
 * the user's own five takes, as 1990s voice dialling did — loudness gate → speech onset → is it speech → MFCC → DTW → threshold.
 * tools/wake-test.py is the same pipeline, measured on the user's takes, an hour and fifty minutes of lectures and a podcast;
 * change both together. Security is Android's lock, not this (personal tools ask for the unlock: Assistant.kt run).
 */
internal object WakeWord {
    const val RATE = 16_000
    const val HOP = 160                 // 10 ms
    private const val WIN = 400         // 25 ms
    private const val NFFT = 512
    private const val N_MEL = 26
    const val N_CEP = 12                // c1..c12; c0 (loudness) dropped
    const val GATE_ABS_DB = -55.0
    const val GATE_OVER_FLOOR_DB = 9.0
    const val ONSET_SILENCE = 15        // frames of quiet before an onset counts
    const val WINDOW = 160              // frames (1.6 s) looked at after an onset
    const val PRE = 5                   // frames kept before it
    /** The wake phrases (the user, 15 Nov: "Just Tetra, ok Tetra and hey Tetra"; then "Ahoy", Bell's telephone greeting), each its own takes and threshold. */
    val PHRASES = listOf("Hey Tetra", "OK Tetra", "Ahoy Tetra", "Tetra")
    /**
     * Each phrase's threshold: this times its takes' median leave-one-out distance. tools/wake-test.py on the user's voice,
     * 15 Nov: Hey Tetra's takes ≤ 1.32, their talk and look-alike words ≥ 1.50; OK Tetra's held-out 0.98, their talk ≥ 1.39;
     * Tetra's takes ≤ 1.03, a look-alike word 1.25 (other people's lectures ≥ 1.53 for all three).
     */
    // the phone, 15 Nov (near and far takes mixed): the user's tries from their chair 1.03–1.32 of the PC's thresholds, all other sound
    // ≥ 1.35 (most ≥ 1.5): the long phrases a fifth looser, the short "Tetra" a tenth
    val FACTOR = mapOf("Hey Tetra" to 1.75, "OK Tetra" to 1.6, "Ahoy Tetra" to 1.7, "Tetra" to 1.3)
    /** Frames over the gate before a window is looked at: a lone "Tetra" is 0.4 s with a stop inside. */
    const val MIN_SPEECH = 15
    /** A take this much further from the others than the median is offered to be recorded again (15 Nov: one take was 1.40). */
    const val ODD = 1.3

    private val HAM = FloatArray(WIN) { (0.54 - 0.46 * cos(2 * PI * it / (WIN - 1))).toFloat() }
    private val BAND = BooleanArray(NFFT / 2 + 1) { val f = it * RATE.toDouble() / NFFT; f in 250.0..3500.0 }
    private val FB: Array<FloatArray> = run {
        fun mel(f: Double) = 2595 * log10(1 + f / 700)
        fun imel(m: Double) = 700 * (Math.pow(10.0, m / 2595) - 1)
        val lo = mel(60.0); val hi = mel(7600.0)
        val bins = IntArray(N_MEL + 2) { floor((NFFT + 1) * imel(lo + (hi - lo) * it / (N_MEL + 1)) / RATE).toInt() }
        Array(N_MEL) { i ->
            val (a, b, c) = Triple(bins[i], bins[i + 1], bins[i + 2])
            FloatArray(NFFT / 2 + 1) { k -> when { k in a until b -> (k - a).toFloat() / maxOf(b - a, 1); k in b until c -> (c - k).toFloat() / maxOf(c - b, 1); else -> 0f } }
        }
    }
    private val DCT = Array(N_CEP) { k -> DoubleArray(N_MEL) { m -> cos(PI / N_MEL * (m + 0.5) * (k + 1)) } }

    /** Each 10 ms frame's loudness in dBFS, its mean (a mic's DC) left out. */
    fun frameDb(x: FloatArray, from: Int = 0): Double {
        var s = 0.0; var s2 = 0.0
        for (k in from until from + HOP) { val v = x[k].toDouble(); s += v; s2 += v * v }
        val mean = s / HOP
        return 10 * log10(s2 / HOP - mean * mean + 1e-10)
    }
    fun framesDb(x: FloatArray) = DoubleArray(x.size / HOP) { frameDb(x, it * HOP) }

    /** Power spectra of 25 ms windows every 10 ms, pre-emphasised. */
    fun spectrum(x: FloatArray): Array<FloatArray> {
        val e = FloatArray(x.size) { if (it == 0) x[0] else x[it] - 0.97f * x[it - 1] }
        val n = maxOf(0, (e.size - WIN) / HOP + 1)
        val re = DoubleArray(NFFT); val im = DoubleArray(NFFT)
        return Array(n) { f ->
            re.fill(0.0); im.fill(0.0)
            for (k in 0 until WIN) re[k] = (e[f * HOP + k] * HAM[k]).toDouble()
            fft(re, im)
            FloatArray(NFFT / 2 + 1) { (re[it] * re[it] + im[it] * im[it]).toFloat() }
        }
    }

    fun mfcc(power: Array<FloatArray>): Array<FloatArray> {
        val mel = DoubleArray(N_MEL)
        return Array(power.size) { f ->
            for (m in 0 until N_MEL) { var s = 0.0; val w = FB[m]; val p = power[f]; for (k in p.indices) if (w[k] != 0f) s += w[k] * p[k]; mel[m] = ln(s + 1e-10) }
            FloatArray(N_CEP) { k -> var s = 0.0; for (m in 0 until N_MEL) s += mel[m] * DCT[k][m]; s.toFloat() }
        }
    }

    /** Share of the [loud] frames' power in the voice's band (250–3500 Hz). */
    fun voiceShare(power: Array<FloatArray>, loud: BooleanArray): Double {
        var band = 0.0; var all = 0.0
        for (f in power.indices) if (loud[f]) for (k in power[f].indices) { val p = power[f][k].toDouble(); all += p; if (BAND[k]) band += p }
        return if (all > 0) band / all else 0.0
    }

    /** A take's speech: the stretch around its loudest frame, gaps under 250 ms bridged, the first 250 ms (the mic settling) left out. */
    fun trim(x: FloatArray): FloatArray {
        val db = framesDb(x)
        if (db.size <= 26) return FloatArray(0)
        for (i in 0 until 25) db[i] = -100.0
        val bar = maxOf(GATE_ABS_DB, percentile(db.copyOfRange(25, db.size), 10.0) + 12)
        var a = db.indices.maxBy { db[it] }; var b = a
        if (db[a] <= bar) return FloatArray(0)
        while (true) { val p = (maxOf(0, a - 25) until a).firstOrNull { db[it] > bar } ?: break; a = p }
        while (true) { val p = (minOf(db.size - 1, b + 25) downTo b + 1).firstOrNull { db[it] > bar } ?: break; b = p }
        return x.copyOfRange(maxOf(0, a - 3) * HOP, minOf(x.size, (b + 4) * HOP))
    }

    private fun percentile(v: DoubleArray, q: Double): Double {
        val s = v.sorted(); val r = (s.size - 1) * q / 100; val i = r.toInt()
        return if (i + 1 < s.size) s[i] + (s[i + 1] - s[i]) * (r - i) else s[i]
    }

    /** A take's features, as kept for matching. */
    fun features(pcm: ShortArray): Array<FloatArray> = mfcc(spectrum(trim(floats(pcm))))
    fun floats(pcm: ShortArray) = FloatArray(pcm.size) { pcm[it] / 32768f }

    /**
     * Template [t] against a prefix of [q] (start pinned, end free), averaged over [t]'s frames. Each template frame moves q
     * on by 1 or 2 frames, or 0 but never twice running: said at most twice as fast or slow.
     */
    fun dtw(t: Array<FloatArray>, q: Array<FloatArray>): Double {
        val n = t.size; val m = q.size
        if (n == 0 || m == 0) return Double.POSITIVE_INFINITY
        fun d(i: Int, j: Int): Double { var s = 0.0; val a = t[i]; val b = q[j]; for (k in a.indices) { val x = (a[k] - b[k]).toDouble(); s += x * x }; return sqrt(s) }
        var moved = DoubleArray(m) { Double.POSITIVE_INFINITY }; var stayed = DoubleArray(m) { Double.POSITIVE_INFINITY }
        moved[0] = d(0, 0)
        var mv = DoubleArray(m); var st = DoubleArray(m)
        for (i in 1 until n) {
            for (j in 0 until m) {
                val b1 = if (j >= 1) minOf(moved[j - 1], stayed[j - 1]) else Double.POSITIVE_INFINITY
                val b2 = if (j >= 2) minOf(moved[j - 2], stayed[j - 2]) else Double.POSITIVE_INFINITY
                val dij = if (moved[j] < Double.POSITIVE_INFINITY || b1 < Double.POSITIVE_INFINITY || b2 < Double.POSITIVE_INFINITY) d(i, j) else 0.0
                st[j] = moved[j] + dij
                mv[j] = minOf(b1, b2) + dij
            }
            moved = mv.also { mv = moved }; stayed = st.also { st = stayed }
        }
        val lo = (n * 0.6).toInt(); val hi = minOf(m - 1, (n * 1.7).toInt())
        if (hi < lo) return Double.POSITIVE_INFINITY
        var best = Double.POSITIVE_INFINITY
        for (j in lo..hi) best = minOf(best, moved[j], stayed[j])
        return best / n
    }

    fun score(q: Array<FloatArray>, templates: List<Array<FloatArray>>) = templates.minOf { dtw(it, q) }

    /** Each take against the others (it padded with 40 quiet frames, as a live window has room after it). */
    fun leaveOneOut(takes: List<Array<FloatArray>>): DoubleArray = DoubleArray(takes.size) { k ->
        val q = takes[k] + Array(40) { FloatArray(N_CEP) }
        takes.filterIndexed { j, _ -> j != k }.minOf { dtw(it, q) }
    }
    fun median(v: DoubleArray) = v.sorted().let { if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }
    fun threshold(takes: List<Array<FloatArray>>, phrase: String) = FACTOR.getValue(phrase) * median(leaveOneOut(takes))

    /** In-place radix-2 FFT. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len; val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val xr = re[b] * cr - im[b] * ci; val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr; im[b] = im[a] - xi; re[a] += xr; im[a] += xi
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}

/** One wake phrase's takes (features) and the distance under which it wakes. */
internal class WakePhrase(val name: String, val templates: List<Array<FloatArray>>, val threshold: Double) {
    constructor(name: String, templates: List<Array<FloatArray>>) : this(name, templates, WakeWord.threshold(templates, name))
}

/**
 * The live half: fed the mic's samples, says when "Hey Tetra" was heard. In quiet only the 10 ms loudness is worked out; an
 * onset after 150 ms of quiet is looked at 1.6 s later, from a ring of the last 1.65 s, against every phrase; the best match
 * (its distance over its own threshold) wakes under 1. [heard] gets each look: that phrase and its ratio.
 */
internal class WakeDetector(private val phrases: List<WakePhrase>, private val heard: (WakePhrase, Double) -> Unit = { _, _ -> }) {
    private val frames = WakeWord.PRE + WakeWord.WINDOW + 2
    private val ring = FloatArray(frames * WakeWord.HOP)
    private val dbs = DoubleArray(frames)
    private val cur = FloatArray(WakeWord.HOP)
    private var curN = 0
    private var written = 0L
    private var frame = 0L
    private var floor = Double.NaN
    private var quiet = 99
    private var busyUntil = -1L
    private val pending = ArrayDeque<Pair<Long, Double>>() // an onset's frame, and the noise floor then
    /** For the log's summaries. */
    var loudFrames = 0L; private set
    val allFrames get() = frame
    var checks = 0; private set
    /** The window last looked at (for the phone's near-miss copies, WakeService). */
    var lastWindow: FloatArray? = null; private set

    fun push(pcm: ShortArray, n: Int = pcm.size): Boolean {
        var woke = false
        for (k in 0 until n) {
            val v = pcm[k] / 32768f
            ring[(written % ring.size).toInt()] = v; written++
            cur[curN++] = v
            if (curN == WakeWord.HOP) { curN = 0; if (frameDone()) woke = true }
        }
        return woke
    }

    private fun frameDone(): Boolean {
        val e = WakeWord.frameDb(cur)
        val i = frame
        if (floor.isNaN()) floor = e
        floor += (if (e < floor) 0.05 else 0.0015) * (e - floor) // falls fast, rises ~1.5 dB/s
        val loud = e > WakeWord.GATE_ABS_DB && e > floor + WakeWord.GATE_OVER_FLOOR_DB
        if (loud) loudFrames++
        dbs[(i % frames).toInt()] = e
        if (loud && quiet >= WakeWord.ONSET_SILENCE && i > busyUntil) pending.addLast(i to floor)
        quiet = if (loud) 0 else quiet + 1
        frame++
        while (pending.isNotEmpty() && frame >= pending.first().first + WakeWord.WINDOW) {
            val (on, fl) = pending.removeFirst()
            if (on > busyUntil && check(on, fl)) { busyUntil = on + WakeWord.WINDOW; pending.clear(); return true }
        }
        return false
    }

    private fun check(on: Long, floorThen: Double): Boolean {
        val a = maxOf(0L, on - WakeWord.PRE); val b = on + WakeWord.WINDOW
        val seg = FloatArray(((b - a) * WakeWord.HOP).toInt()) { ring[((a * WakeWord.HOP + it) % ring.size).toInt()] }
        lastWindow = seg
        val power = WakeWord.spectrum(seg)
        val bar = maxOf(WakeWord.GATE_ABS_DB, floorThen + WakeWord.GATE_OVER_FLOOR_DB)
        val loud = BooleanArray(power.size) { dbs[((a + it) % frames).toInt()] > bar }
        // is it speech: 0.15 s or more over the gate, most of it in the voice's band
        if (loud.count { it } < WakeWord.MIN_SPEECH || WakeWord.voiceShare(power, loud) <= 0.5) return false
        checks++
        val f = WakeWord.mfcc(power)
        val (p, r) = phrases.map { it to WakeWord.score(f, it.templates) / it.threshold }.minBy { it.second }
        heard(p, r)
        return r < 1
    }
}
