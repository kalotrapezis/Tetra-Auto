package com.kalotrapezis.drive

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * Tetra Auto's wire (the user, 10 Nov: "the phone is the server and the car a dumb client", as Android Auto): the phone draws
 * Tetra Car and sends it as H.264; the car shows it and sends the touches back. One TCP connection over the phone's hotspot
 * (or any Wi-Fi both are on), frames of [type: 1 byte][length: 4 bytes, big-endian][payload]. The phone says where it is by a
 * UDP broadcast on [BEACON_PORT] every 2 s while it serves. The same file is the car app's Wire.kt (repo Tetra-Auto), word for word
 * but for its package: a change here is a change there.
 */
internal object AutoWire {
    const val PORT = 43210 // 43180–43191 are Tetra's sync and the desktop's own
    const val BEACON_PORT = 43211
    const val BEACON = "TETRA-AUTO"
    const val VERSION = 1
    /** The largest frame: a video access unit or a file's chunk. */
    const val MAX = 8 shl 20
    const val CHUNK = 256 * 1024

    // car → phone
    /** JSON {version, token | code, name, width, height, dpi, pings}: who the car is and the screen it has; pings: it sends PING every 5 s. */
    const val HELLO: Byte = 0x01
    /** [action][actionIndex][count] then count × [id][x: f32][y: f32], x and y from 0 to 1 of the car's screen. */
    const val TOUCH: Byte = 0x02
    /** [keycode: i32], down and up: the head unit's Back, its steering wheel's voice key. */
    const val KEY: Byte = 0x03
    /** A whole picture again (the car's decoder started over). */
    const val KEYFRAME: Byte = 0x04

    // phone → car
    /** JSON {name, token?}: paired (token, the first time) and the picture follows. */
    const val WELCOME: Byte = 0x41
    /** JSON {error, unpaired?}: not this car (unpaired: its token is no longer known, pair again). */
    const val REFUSED: Byte = 0x42
    /** The H.264 codec config (SPS and PPS, Annex B), before the first picture and after each new encoder. */
    const val VIDEO_CONFIG: Byte = 0x43
    /** [key: 1][pts: i64 µs][one H.264 access unit, Annex B]. */
    const val VIDEO: Byte = 0x44
    /** Tetra Car's dock asked for the car's own Share page. */
    const val OPEN_SHARE: Byte = 0x45
    /** JSON {text}: a line for the car's screen (what to allow on the phone). */
    const val NOTICE: Byte = 0x46

    // both ways: Share
    /** JSON {id, name, size}. */
    const val FILE_BEGIN: Byte = 0x61
    /** [id: i32][bytes]. */
    const val FILE_DATA: Byte = 0x62
    /** JSON {id, sha256}. */
    const val FILE_END: Byte = 0x63
    /** JSON {id, ok, error?}: the receiver's answer. */
    const val FILE_DONE: Byte = 0x64
    /** Both ways, every 5 s: still here (a car that stops is gone after 20 s). */
    const val PING: Byte = 0x70

    fun write(out: DataOutputStream, type: Byte, payload: ByteArray, off: Int = 0, len: Int = payload.size) = synchronized(out) {
        out.writeByte(type.toInt()); out.writeInt(len); out.write(payload, off, len); out.flush()
    }

    /** One frame; throws at the end of the stream or for a frame no one sends. */
    fun read(input: DataInputStream): Pair<Byte, ByteArray> {
        val type = input.readByte()
        val len = input.readInt()
        if (len < 0 || len > MAX) throw IOException("A frame of $len bytes")
        return type to ByteArray(len).also(input::readFully)
    }

    /** A file's name as it may be kept: no folders, no hidden or empty names, at most 120 characters. */
    fun safeName(name: String): String = name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\u0000-\\u001f:*?\"<>|]"), "_")
        .trim().trimStart('.').take(120).ifEmpty { "file" }
}
