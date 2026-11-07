package com.streamlens.app.mux

import java.io.ByteArrayOutputStream

/**
 * Minimal MPEG-TS muxer for a single video elementary stream.
 *
 * OBS (via FFmpeg) reads MPEG-TS over SRT, so every encoded frame is wrapped in a PES packet and
 * split into 188-byte TS packets. PAT/PMT are repeated before every keyframe so OBS can join or
 * rejoin the stream at any keyframe.
 */
class TsMuxer(private val streamType: Int) {

    companion object {
        const val STREAM_TYPE_H264 = 0x1B
        const val STREAM_TYPE_HEVC = 0x24

        const val PACKET_SIZE = 188
        private const val PMT_PID = 0x1000
        private const val VIDEO_PID = 0x0100

        // PTS runs ahead of PCR so the decoder has buffering headroom.
        private const val PTS_OFFSET_90K = 63_000L // 700 ms
    }

    private val out = ByteArrayOutputStream(256 * 1024)
    private val packet = ByteArray(PACKET_SIZE)
    private val cc = IntArray(0x2000)
    private var firstUs = -1L

    /** Muxes one access unit (Annex-B bytes) and returns a whole number of TS packets. */
    fun mux(frame: ByteArray, ptsUs: Long, keyFrame: Boolean): ByteArray {
        out.reset()
        if (firstUs < 0) firstUs = ptsUs
        val clock90k = (ptsUs - firstUs).coerceAtLeast(0) * 9 / 100
        val pcr = clock90k
        val pts = clock90k + PTS_OFFSET_90K

        if (keyFrame) {
            writePsi(0x0000, pat())
            writePsi(PMT_PID, pmt())
        }

        val pes = pesHeader(pts)
        val payload = ByteArray(pes.size + frame.size)
        System.arraycopy(pes, 0, payload, 0, pes.size)
        System.arraycopy(frame, 0, payload, pes.size, frame.size)

        var offset = 0
        var first = true
        while (offset < payload.size) {
            offset += writePacket(
                pid = VIDEO_PID,
                unitStart = first,
                payload = payload,
                offset = offset,
                length = payload.size - offset,
                pcr90k = if (first) pcr else null,
                randomAccess = first && keyFrame,
            )
            first = false
        }
        return out.toByteArray()
    }

    /** Starts timestamps again from zero, used after a reconnect. */
    fun reset() {
        firstUs = -1L
        cc.fill(0)
    }

    private fun writePacket(
        pid: Int,
        unitStart: Boolean,
        payload: ByteArray,
        offset: Int,
        length: Int,
        pcr90k: Long?,
        randomAccess: Boolean,
    ): Int {
        var hasAf = pcr90k != null || randomAccess
        var afBody = if (hasAf) 1 + (if (pcr90k != null) 6 else 0) else 0
        val room = 184 - (if (hasAf) 1 + afBody else 0)
        val n = minOf(length, room)
        if (n < room) {
            val stuffing = room - n
            if (!hasAf) {
                hasAf = true
                afBody = stuffing - 1
            } else {
                afBody += stuffing
            }
        }

        var i = 0
        packet[i++] = 0x47
        packet[i++] = ((if (unitStart) 0x40 else 0) or ((pid shr 8) and 0x1F)).toByte()
        packet[i++] = (pid and 0xFF).toByte()
        packet[i++] = ((if (hasAf) 0x30 else 0x10) or nextCc(pid)).toByte()
        if (hasAf) {
            packet[i++] = afBody.toByte()
            if (afBody > 0) {
                val afStart = i
                packet[i++] = ((if (randomAccess) 0x40 else 0) or (if (pcr90k != null) 0x10 else 0)).toByte()
                if (pcr90k != null) {
                    val base = pcr90k and 0x1FFFFFFFFL
                    packet[i++] = (base shr 25).toByte()
                    packet[i++] = (base shr 17).toByte()
                    packet[i++] = (base shr 9).toByte()
                    packet[i++] = (base shr 1).toByte()
                    packet[i++] = (((base and 1L) shl 7) or 0x7EL).toByte()
                    packet[i++] = 0
                }
                while (i < afStart + afBody) packet[i++] = 0xFF.toByte()
            }
        }
        System.arraycopy(payload, offset, packet, i, n)
        out.write(packet, 0, PACKET_SIZE)
        return n
    }

    private fun writePsi(pid: Int, section: ByteArray) {
        var i = 0
        packet[i++] = 0x47
        packet[i++] = (0x40 or ((pid shr 8) and 0x1F)).toByte()
        packet[i++] = (pid and 0xFF).toByte()
        packet[i++] = (0x10 or nextCc(pid)).toByte()
        packet[i++] = 0 // pointer field
        System.arraycopy(section, 0, packet, i, section.size)
        i += section.size
        while (i < PACKET_SIZE) packet[i++] = 0xFF.toByte()
        out.write(packet, 0, PACKET_SIZE)
    }

    private fun nextCc(pid: Int): Int {
        val value = cc[pid]
        cc[pid] = (value + 1) and 0x0F
        return value
    }

    private fun pat(): ByteArray = withCrc(
        byteArrayOf(
            0x00, 0xB0.toByte(), 13,
            0x00, 0x01, 0xC1.toByte(), 0x00, 0x00,
            0x00, 0x01, (0xE0 or (PMT_PID shr 8)).toByte(), (PMT_PID and 0xFF).toByte(),
        )
    )

    private fun pmt(): ByteArray = withCrc(
        byteArrayOf(
            0x02, 0xB0.toByte(), 18,
            0x00, 0x01, 0xC1.toByte(), 0x00, 0x00,
            (0xE0 or (VIDEO_PID shr 8)).toByte(), (VIDEO_PID and 0xFF).toByte(),
            0xF0.toByte(), 0x00,
            streamType.toByte(), (0xE0 or (VIDEO_PID shr 8)).toByte(), (VIDEO_PID and 0xFF).toByte(),
            0xF0.toByte(), 0x00,
        )
    )

    private fun pesHeader(pts: Long): ByteArray {
        val p = pts and 0x1FFFFFFFFL
        return byteArrayOf(
            0x00, 0x00, 0x01, 0xE0.toByte(),
            0x00, 0x00, // unbounded length, allowed for video
            0x80.toByte(), 0x80.toByte(), 0x05,
            (0x21L or ((p shr 29) and 0x0E)).toByte(),
            (p shr 22).toByte(),
            (((p shr 14) and 0xFE) or 1).toByte(),
            (p shr 7).toByte(),
            (((p shl 1) and 0xFE) or 1).toByte(),
        )
    }

    private fun withCrc(section: ByteArray): ByteArray {
        val crc = crc32Mpeg2(section)
        return section + byteArrayOf((crc shr 24).toByte(), (crc shr 16).toByte(), (crc shr 8).toByte(), crc.toByte())
    }

    private fun crc32Mpeg2(data: ByteArray): Int {
        var crc = -1
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 24)
            repeat(8) {
                crc = if (crc and Int.MIN_VALUE != 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1
            }
        }
        return crc
    }
}
