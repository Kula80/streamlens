package com.streamlens.app.mux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class TsMuxerTest {

    @Test
    fun packetsAreAlignedAndStartWithSyncByte() {
        val muxer = TsMuxer(TsMuxer.STREAM_TYPE_HEVC)
        for ((i, size) in listOf(10, 183, 184, 185, 5000, 1).withIndex()) {
            val ts = muxer.mux(ByteArray(size) { 7 }, i * 33_333L, keyFrame = i == 0)
            assertEquals(0, ts.size % TsMuxer.PACKET_SIZE)
            for (p in ts.indices step TsMuxer.PACKET_SIZE) assertEquals(0x47.toByte(), ts[p])
        }
    }

    /**
     * Muxes a real encoded stream so it can be checked with ffprobe. Set STREAMLENS_SAMPLES to a
     * folder containing sample.hevc and/or sample.h264 (Annex-B, 30 fps, no B-frames).
     */
    @Test
    fun muxesRealStreamsForFfprobe() {
        val dir = System.getenv("STREAMLENS_SAMPLES")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        for ((name, type, hevc) in listOf(
            Triple("sample.hevc", TsMuxer.STREAM_TYPE_HEVC, true),
            Triple("sample.h264", TsMuxer.STREAM_TYPE_H264, false),
        )) {
            val input = File(dir, name)
            if (!input.exists()) continue
            val aud = if (hevc) byteArrayOf(0, 0, 0, 1, 0x46, 0x01, 0x50) else byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte())
            val muxer = TsMuxer(type)
            File(dir, "$name.ts").outputStream().use { out ->
                accessUnits(input.readBytes(), hevc).forEachIndexed { i, (au, key) ->
                    out.write(muxer.mux(aud + au, i * 1_000_000L / 30, key))
                }
            }
        }
    }

    private fun accessUnits(data: ByteArray, hevc: Boolean): List<Pair<ByteArray, Boolean>> {
        val starts = mutableListOf<Int>()
        var i = 0
        while (i + 3 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                starts += i + 3
                i += 3
            } else i++
        }
        val units = mutableListOf<Pair<ByteArray, Boolean>>()
        var current = mutableListOf<ByteArray>()
        var hasVcl = false
        var key = false
        for ((n, start) in starts.withIndex()) {
            var end = if (n + 1 < starts.size) starts[n + 1] - 3 else data.size
            while (end > start && data[end - 1] == 0.toByte()) end--
            val nal = data.copyOfRange(start, end)
            val type = if (hevc) (nal[0].toInt() shr 1) and 0x3F else nal[0].toInt() and 0x1F
            val vcl = if (hevc) type < 32 else type in 1..5
            val firstSlice = vcl && (nal[if (hevc) 2 else 1].toInt() and 0x80) != 0
            if (firstSlice && hasVcl) {
                units += current.fold(ByteArray(0)) { a, b -> a + b } to key
                current = mutableListOf()
                hasVcl = false
                key = false
            }
            current += byteArrayOf(0, 0, 0, 1) + nal
            if (vcl) {
                hasVcl = true
                if (if (hevc) type in 16..23 else type == 5) key = true
            }
        }
        if (hasVcl) units += current.fold(ByteArray(0)) { a, b -> a + b } to key
        assertTrue(units.isNotEmpty())
        return units
    }
}
