package com.kalotrapezis.drive

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.CellInfo
import android.telephony.TelephonyManager
import org.json.JSONArray
import org.json.JSONObject

/** The mobile signal now: the reference signal's power (RSRP, dBm), Android's 0–4 bars, the generation, and the band when known. */
internal data class MobileReading(val dbm: Int, val level: Int, val gen: String, val detail: String, val band: String?, val operator: String)

/** One speed and loss test to the computer, with how each end was connected (to compare Ethernet, Wi-Fi, the hotspot). */
internal data class SpeedTest(val at: String, val here: String, val there: String, val down: Double, val up: Double, val ms: Long, val loss: Int?)

/**
 * Words for the screen as a string's id and its arguments, put into words where they are shown (the rules stay pure, and the
 * tests compare ids): a [DataWords] argument is worded too, a date shown "d MMM" in the UI's language; [count] picks a plural.
 */
internal data class DataWords(@androidx.annotation.StringRes val id: Int, val args: List<Any> = emptyList(), val count: Int? = null) {
    fun text(c: Context): String {
        val a = args.map { x -> when (x) {
            is DataWords -> x.text(c)
            is java.time.LocalDate -> x.format(java.time.format.DateTimeFormatter.ofPattern("d MMM", c.resources.configuration.locales[0]))
            else -> x
        } }.toTypedArray()
        return if (count != null) c.resources.getQuantityString(id, count, *a) else c.getString(id, *a)
    }
}

/** A piece of advice from the Analyzer: information, never a change made for the user (the user, 25 Oct: "giving info, not … auto fix"). */
internal data class Advice(val title: DataWords, val text: DataWords)

/**
 * Data › Signal › Meter and Analyzer (Plan.md § Data): what the phone, the tablet and the computer are connected by, and how
 * well. Android gives the mobile signal's strength without a permission; the band (and the cell) needs the location right.
 */
internal object DataMeter {
    fun mobile(c: Context): MobileReading? {
        val tm = c.getSystemService(TelephonyManager::class.java) ?: return null
        val ss = runCatching { tm.signalStrength }.getOrNull() ?: return null
        val nr = ss.getCellSignalStrengths(CellSignalStrengthNr::class.java).firstOrNull { it.ssRsrp != CellInfo.UNAVAILABLE }
        val lte = ss.getCellSignalStrengths(CellSignalStrengthLte::class.java).firstOrNull { it.rsrp != CellInfo.UNAVAILABLE }
        val gen = when { nr != null && lte != null -> c.getString(R.string.data_5g_with_4g); nr != null -> "5G"; lte != null -> "4G"; else -> "" }
        val dbm = nr?.ssRsrp ?: lte?.rsrp ?: ss.cellSignalStrengths.firstOrNull()?.dbm ?: return null
        val detail = when {
            nr != null -> listOfNotNull(nr.ssRsrq.takeIf { it != CellInfo.UNAVAILABLE }?.let { "RSRQ $it" }, nr.ssSinr.takeIf { it != CellInfo.UNAVAILABLE }?.let { "SINR $it dB" }).joinToString(" · ")
            lte != null -> listOfNotNull(lte.rsrq.takeIf { it != CellInfo.UNAVAILABLE }?.let { "RSRQ $it" }, lte.rssnr.takeIf { it != CellInfo.UNAVAILABLE }?.let { "SINR $it dB" }).joinToString(" · ")
            else -> ""
        }
        // the band: from the registered cell, which Android gives only with the location right
        val band = if (!DataSignal.located(c)) null else runCatching {
            tm.allCellInfo.orEmpty().filter { it.isRegistered }.firstNotNullOfOrNull { info ->
                when (info) {
                    is CellInfoNr -> (info.cellIdentity as? CellIdentityNr)?.bands?.firstOrNull()?.let { "n$it" }
                    is CellInfoLte -> (info.cellIdentity as CellIdentityLte).bands.firstOrNull()?.let { "B$it" }
                    else -> null
                }
            }
        }.getOrNull()
        return MobileReading(dbm, ss.level, gen, detail, band, tm.networkOperatorName.orEmpty())
    }

    /** How this device is connected now: Ethernet, a phone's hotspot (a metered Wi-Fi), Wi-Fi, mobile, or nothing. */
    fun link(c: Context): String {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "Offline"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) -> "Hotspot"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile"
            else -> "Other"
        }
    }

    /** [link]'s answer in words: Wi-Fi and Ethernet as they are, the others in the UI's language. */
    fun linkName(c: Context, link: String): String = when (link) {
        "Hotspot" -> c.getString(R.string.data_hotspot); "Mobile" -> c.getString(R.string.data_mobile)
        "Offline" -> c.getString(R.string.data_offline); "Other" -> c.getString(R.string.data_other); else -> link
    }

    /** The computer's own connection as its /data/wifi says it: "Ethernet 1000 Mb/s" or "Wi-Fi −58 dBm". */
    fun computerLink(o: JSONObject?): String = when {
        o == null -> "?"
        o.optJSONObject("ethernet") != null -> "Ethernet" + (o.optJSONObject("ethernet")!!.optInt("speed").takeIf { it > 0 }?.let { " $it Mb/s" } ?: "")
        // right beside the router a driver may say a positive dBm: then just Wi-Fi
        o.has("rssi") -> "Wi-Fi" + (o.optInt("rssi").takeIf { it < 0 }?.let { " $it dBm" } ?: "")
        else -> "?"
    }

    /** "20 packets transmitted, 19 received, 5% packet loss … rtt min/avg/max/mdev = 1.2/3.4/9.9/1.1 ms" → (5, 3.4). */
    fun parsePing(out: String): Pair<Int, Double>? {
        val loss = Regex("""([\d.]+)% packet loss""").find(out)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        val avg = Regex("""= [\d.]+/([\d.]+)/""").find(out)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        return Math.round(loss).toInt() to avg
    }

    /** Lost packets to [host]: Android's own ping, 20 of them over 4 s (an app may ping, it may not send raw packets itself). */
    fun ping(host: String): Pair<Int, Double>? = runCatching {
        val p = ProcessBuilder("ping", "-c", "20", "-i", "0.2", "-W", "1", host).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText(); p.waitFor()
        parsePing(out)
    }.getOrNull()

    // ---- the tests, kept on this device (the last ten) ----

    private fun prefs(c: Context) = c.getSharedPreferences("data", Context.MODE_PRIVATE)

    fun tests(c: Context): List<SpeedTest> = runCatching {
        val a = JSONArray(prefs(c).getString("speedTests", "[]"))
        List(a.length()) { a.getJSONObject(it) }.map { o ->
            SpeedTest(o.getString("at"), o.optString("here"), o.optString("there"), o.optDouble("down"), o.optDouble("up"), o.optLong("ms"), if (o.has("loss")) o.getInt("loss") else null)
        }
    }.getOrDefault(emptyList())

    fun addTest(c: Context, t: SpeedTest) {
        val all = (listOf(t) + tests(c)).take(10)
        prefs(c).edit().putString("speedTests", JSONArray(all.map { s ->
            JSONObject().put("at", s.at).put("here", s.here).put("there", s.there).put("down", s.down).put("up", s.up).put("ms", s.ms).apply { s.loss?.let { put("loss", it) } }
        }).toString()).apply()
    }

    // ---- the Analyzer's advice ----

    /**
     * Short advice from what is heard around and the signal here. [mine] is this device's Wi-Fi now, [hotspot] whether it is
     * a phone's hotspot, [heard] each network as (name, MHz, dBm). Only information: Tetra changes nothing.
     */
    fun advice(mine: WifiReading?, hotspot: Boolean, heard: List<Triple<String, Int, Int>>): List<Advice> {
        val out = mutableListOf<Advice>()
        val others = heard.filter { mine?.ssid == null || it.first != mine.ssid }
        if (mine != null) {
            val ch = DataSignal.channel(mine.mhz)
            val nets = others.map { DataSignal.channel(it.second) to it.third }
            val best = DataSignal.crowding(nets).firstOrNull { it.first in 1..14 }
            val here = DataSignal.crowdingAt(nets, ch)
            if (ch in 1..14 && best != null && best.first != ch && here - best.second >= 1.0) {
                val near = others.count { DataSignal.channel(it.second) in 1..14 && kotlin.math.abs(DataSignal.channel(it.second) - ch) < 5 }
                out += Advice(DataWords(R.string.data_adv_channel, listOf(best.first)),
                    if (hotspot) DataWords(R.plurals.data_adv_channel_hotspot, listOf(ch, near), near) else DataWords(R.plurals.data_adv_channel_router, listOf(ch, near, best.first), near))
            }
            if (ch in 1..14) out += Advice(DataWords(R.string.data_band_24), DataWords(if (hotspot) R.string.data_adv_24_hotspot else R.string.data_adv_24_router))
            when {
                mine.rssi < -75 -> out += Advice(DataWords(R.string.data_adv_weak, listOf(mine.rssi)), DataWords(if (hotspot) R.string.data_adv_weak_hotspot else R.string.data_adv_weak_router))
                mine.rssi < -67 -> out += Advice(DataWords(R.string.data_adv_fair, listOf(mine.rssi)), DataWords(if (hotspot) R.string.data_adv_fair_hotspot else R.string.data_adv_fair_router))
                else -> out += Advice(DataWords(R.string.data_adv_good, listOf(mine.rssi)), DataWords(R.string.data_adv_good_text))
            }
        }
        val crowded24 = others.count { DataSignal.channel(it.second) in 1..14 && it.third > -80 }
        if (crowded24 >= 10) out += Advice(DataWords(R.string.data_adv_busy), DataWords(R.string.data_adv_busy_text, listOf(crowded24)))
        return out
    }
}
