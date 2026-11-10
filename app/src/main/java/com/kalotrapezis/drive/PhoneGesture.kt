package com.kalotrapezis.drive

import kotlin.math.abs

/**
 * The decision behind PhoneGestures: sensor samples of a ringing call in (times in ns), an answer or a silence out. Pure, so
 * the tests play a call's whole motion. 7 Nov: calls were answered in the pocket ("upright + near" was all it asked), so raise
 * to answer now needs the whole path —
 * 1. looked at: light above a pocket's dark, the proximity sensor far, face up or tilted toward the face (not upside down),
 *    for 0.6 s (1 s without a light sensor);
 * 2. then raised: it turns up toward upright or on its side, and within 2.5 s of that the proximity sensor goes near (a new
 *    near, after the look) and the light falls as the ear covers it.
 * Covered or dark from the first ring (a pocket, a bag) it is never seen looked at, so it never answers there; covered after the
 * look but before any raise (put away) it has to be looked at again. Face down silences, as before: gravity on the screen's side
 * for 0.4 s after it was seen face up; face down is not upright, so a flip never answers.
 */
internal class PhoneGesture(private val raise: Boolean, private val flip: Boolean, private val hasLight: Boolean, private val log: (String) -> Unit = {}) {
    enum class Verdict { ANSWER, SILENCE }
    private enum class Phase { WAITING, LOOKING, LOOKED, RAISING, DONE }

    private var phase = Phase.WAITING
    private var since = 0L
    private var near: Boolean? = null
    private var lux: Float? = null
    private var x = 0f; private var y = 0f; private var z = 0f; private var tilt = false
    private var lookLux = 0f; private var lookZ = 0f
    private var faceUp = false; private var downSince: Long? = null

    fun proximity(t: Long, near: Boolean) = step(t) { this.near = near }
    fun light(t: Long, lux: Float) = step(t) { this.lux = lux }
    fun gravity(t: Long, x: Float, y: Float, z: Float) = step(t) { this.x = x; this.y = y; this.z = z; tilt = true }

    private fun step(t: Long, sample: () -> Unit): Verdict? {
        if (phase == Phase.DONE) return null
        sample()
        if (flip && tilt) {
            if (z > 7f) faceUp = true
            if (faceUp && z < -8f) {
                val d = downSince ?: t.also { downSince = it }
                if (t - d > 400_000_000L) { to(Phase.DONE, t, "silenced, turned face down"); return Verdict.SILENCE }
            } else downSince = null
        }
        if (!raise) return null
        val looking = near == false && tilt && z > 3f && y > -3f && (!hasLight || (lux ?: 0f) > DARK)
        val standing = tilt && z > -2f && z < 6f && y > -3f && (y > 3f || abs(x) > 3f)
        when (phase) {
            Phase.WAITING -> if (looking) to(Phase.LOOKING, t, "looked at?")
            Phase.LOOKING -> when {
                !looking -> to(Phase.WAITING, t, "the look broke off")
                t - since >= if (hasLight) 600_000_000L else 1_000_000_000L -> { lookLux = lux ?: 0f; lookZ = z; to(Phase.LOOKED, t, "looked at") }
            }
            Phase.LOOKED -> when {
                near == true -> to(Phase.WAITING, t, "covered before any raise (put away?)")
                standing && z < lookZ - 2f -> to(Phase.RAISING, t, "raised")
            }
            Phase.RAISING -> when {
                near == true && standing && (!hasLight || (lux ?: 0f) <= maxOf(DARK, lookLux / 4)) -> { to(Phase.DONE, t, "answered, at the ear"); return Verdict.ANSWER }
                t - since > 2_500_000_000L -> to(Phase.WAITING, t, "raised but not to the ear within 2.5 s")
            }
            Phase.DONE -> {}
        }
        return null
    }

    private fun to(p: Phase, t: Long, why: String) {
        phase = p; since = t
        log("Phone gesture: $p — $why (near=$near lux=$lux g=" + String.format(java.util.Locale.ROOT, "%.1f/%.1f/%.1f)", x, y, z))
    }

    private companion object { const val DARK = 3f } // lux: a pocket or a bag reads 0–2
}
