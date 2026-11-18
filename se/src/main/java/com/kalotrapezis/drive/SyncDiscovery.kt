package com.kalotrapezis.drive

import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * Finding the paired computer again after its address changes (SYNC_PLAN.md 6k). A MAC address would be no help
 * here — Android randomises it per network and anything on the wire can claim any MAC — so identity stays what
 * pairing already made it: the certificate fingerprint the phone pinned.
 *
 * The probe names that fingerprint and only the computer it belongs to answers, so the datagram tells the asker
 * what it already knew and a stranger listening learns nothing. The token never rides on UDP, and an answer is
 * only a hint about where to knock: the pinned certificate still decides whether a sync happens.
 */
internal object SyncDiscovery {
    const val BEACON_PORT = 43181

    fun probe(fingerprint: String): ByteArray =
        JSONObject().put("v", 1).put("fp", fingerprint).toString().toByteArray()

    /** The HTTPS port in a reply, or null for anything else — including an answer to somebody else's question. */
    fun replyPort(bytes: ByteArray, length: Int): Int? = runCatching {
        JSONObject(String(bytes, 0, length, Charsets.UTF_8))
            .takeIf { it.optInt("v") == 1 }?.optInt("port")?.takeIf { it in 1..65535 }
    }.getOrNull()

    /** Every broadcast address this phone can reach, so a probe goes out on Wi-Fi and on anything else too. */
    private fun broadcastAddresses(): List<InetAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses }
            .mapNotNull { it.broadcast }
    }.getOrDefault(emptyList()).plus(runCatching { InetAddress.getByName("255.255.255.255") }.getOrNull()).filterNotNull().distinct()

    /** Addresses that answered for this fingerprint, with the port each one is listening on. */
    fun find(fingerprint: String, timeoutMs: Int = 900, port: Int = BEACON_PORT): List<Pair<String, Int>> {
        val found = LinkedHashMap<String, Int>()
        runCatching {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 200
                val message = probe(fingerprint)
                for (address in broadcastAddresses()) {
                    runCatching { socket.send(DatagramPacket(message, message.size, address, port)) }
                }
                val deadline = System.currentTimeMillis() + timeoutMs
                val buffer = ByteArray(512)
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
                    replyPort(packet.data, packet.length)?.let { found[packet.address.hostAddress ?: return@let] = it }
                }
            }
        }
        return found.toList()
    }
}
