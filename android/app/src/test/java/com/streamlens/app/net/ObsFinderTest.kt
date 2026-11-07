package com.streamlens.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class ObsFinderTest {

    private fun ip(a: Int, b: Int, c: Int, d: Int) = (a shl 24) or (b shl 16) or (c shl 8) or d

    @Test
    fun scansTheWholeSubnetExceptItself() {
        val hosts = ObsFinder.hostsToScan(ip(192, 168, 1, 37), 24)
        assertEquals(253, hosts.size)
        assertEquals(ip(192, 168, 1, 1), hosts.first())
        assertEquals(ip(192, 168, 1, 254), hosts.last())
        assertFalse(ip(192, 168, 1, 37) in hosts)
    }

    @Test
    fun bigNetworksAreLimitedToTheNearest254() {
        val hosts = ObsFinder.hostsToScan(ip(10, 0, 5, 9), 16)
        assertEquals(253, hosts.size)
        assertTrue(hosts.all { it ushr 8 == ip(10, 0, 5, 0) ushr 8 })
    }

    @Test
    fun ignoresPacketsThatAreNotAnSrtListenerReply() {
        assertFalse(ObsFinder.isInductionReply(ByteArray(64), 64))
        // Our own request is a handshake too, but without the listener's 0x4A17 magic.
        val request = ObsFinder.inductionPacket(1)
        assertFalse(ObsFinder.isInductionReply(request, request.size))
        assertFalse(ObsFinder.isInductionReply(request, 20))
    }

    /** Talks to a real SRT listener (OBS or ffmpeg). Set STREAMLENS_SRT_PORT to its port on this computer. */
    @Test
    fun realListenerAnswers() {
        val port = System.getenv("STREAMLENS_SRT_PORT")?.toIntOrNull()
        assumeTrue(port != null)
        DatagramSocket().use { socket ->
            socket.soTimeout = 1500
            val packet = ObsFinder.inductionPacket(0x1234)
            socket.send(DatagramPacket(packet, packet.size, InetAddress.getLoopbackAddress(), port!!))
            val reply = DatagramPacket(ByteArray(256), 256)
            socket.receive(reply)
            assertTrue(ObsFinder.isInductionReply(reply.data, reply.length))
        }
    }
}
