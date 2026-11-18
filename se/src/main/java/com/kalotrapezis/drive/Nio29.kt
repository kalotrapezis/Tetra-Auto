package com.kalotrapezis.drive

import java.nio.ByteBuffer
import java.util.Collections
import java.util.stream.Collectors
import java.util.stream.Stream

/**
 * Java 13's absolute bulk ByteBuffer.get/put(index, bytes, offset, length), which Android has only from 14: GraphHopper's
 * memory-mapped graph (MMapDataAccess) reads names and properties with them, so routing failed on Android 10 head units with
 * NoSuchMethodError. The car build rewrites those calls to these (car/build.gradle.kts, Nio29Calls); same result, the buffer's
 * own position untouched. Also Java 16's Stream.toList() (Android 14), which GraphHopper calls when it opens a graph and for a
 * route's details: the map's toast "No interface method toList()" on the car, 13 Nov.
 */
object Nio29 {
    @JvmStatic fun get(b: ByteBuffer, index: Int, dst: ByteArray, off: Int, len: Int): ByteBuffer { b.duplicate().apply { position(index) }.get(dst, off, len); return b }
    @JvmStatic fun put(b: ByteBuffer, index: Int, src: ByteArray, off: Int, len: Int): ByteBuffer { b.duplicate().apply { position(index) }.put(src, off, len); return b }
    @JvmStatic fun <T> toList(s: Stream<T>): List<T> = Collections.unmodifiableList(s.collect(Collectors.toList()))
}
