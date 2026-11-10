package com.kalotrapezis.tetra.auto

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * The phone's picture on the car's screen: H.264 decoded by the unit's own hardware (MediaCodec) straight onto the screen's
 * surface. A frame that cannot be fed (no decoder, a full queue) is dropped and the next whole picture asked for, so the
 * screen catches up instead of falling behind.
 */
internal class Video {
    @Volatile private var surface: Surface? = null
    private var width = 0; private var height = 0
    private var codec: MediaCodec? = null
    private var config: ByteArray? = null
    @Volatile private var waitingForKey = true
    private var drain: Thread? = null

    /** The screen's surface came (or went: null). */
    @Synchronized fun surface(s: Surface?, w: Int, h: Int) {
        surface = s; width = w; height = h
        stopCodec()
        config?.let { if (s != null) start(it) }
    }

    @Synchronized fun config(csd: ByteArray) { config = csd; stopCodec(); if (surface != null) start(csd) }

    /** One access unit ([key][pts][data]); false when a whole picture is needed first. */
    @Synchronized fun frame(body: ByteArray): Boolean {
        val c = codec ?: return false
        val key = body[0].toInt() == 1
        if (waitingForKey && !key) return false
        val pts = ByteBuffer.wrap(body, 1, 8).long
        val i = runCatching { c.dequeueInputBuffer(30_000) }.getOrDefault(-1)
        if (i < 0) { waitingForKey = true; return false }
        c.getInputBuffer(i)!!.apply { clear(); put(body, 9, body.size - 9) }
        c.queueInputBuffer(i, 0, body.size - 9, pts, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
        waitingForKey = false
        return true
    }

    @Synchronized fun stop() { stopCodec(); config = null }

    private fun start(csd: ByteArray) {
        val s = surface ?: return
        runCatching {
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width.coerceAtLeast(16), height.coerceAtLeast(16))
            f.setInteger("low-latency", 1) // MediaFormat.KEY_LOW_LATENCY (Android 11): asked for; Android 10 ignores it
            c.configure(f, s, null, 0)
            c.start()
            val i = c.dequeueInputBuffer(500_000)
            if (i >= 0) { c.getInputBuffer(i)!!.apply { clear(); put(csd) }; c.queueInputBuffer(i, 0, csd.size, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG) }
            codec = c; waitingForKey = true
            drain = Thread({
                val info = MediaCodec.BufferInfo()
                while (codec === c) {
                    val o = runCatching { c.dequeueOutputBuffer(info, 20_000) }.getOrDefault(MediaCodec.INFO_TRY_AGAIN_LATER)
                    if (o >= 0) runCatching { c.releaseOutputBuffer(o, true) }
                }
            }, "tetra-auto-video").apply { start() }
            Log.i(TAG, "Decoder started (${width}x$height)")
        }.onFailure { Log.e(TAG, "Decoder: ${it.message}", it) }
    }

    private fun stopCodec() {
        val c = codec ?: return
        codec = null
        runCatching { drain?.join(200) }
        runCatching { c.stop() }; runCatching { c.release() }
        waitingForKey = true
    }
}
