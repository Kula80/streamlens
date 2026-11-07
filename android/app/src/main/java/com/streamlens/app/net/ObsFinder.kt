package com.streamlens.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Finds the OBS computer on the local network so nobody has to type its IP address.
 *
 * OBS (set up by streamlens.lua) waits for the phone as an SRT listener, and every SRT listener answers
 * an SRT "induction" handshake. So we send that tiny packet to every address on the phone's local
 * networks and see who replies. Nothing extra has to run on the computer. The probe is the first step
 * of a normal SRT connection and the listener keeps no state for it, so it can't disturb OBS.
 */
object ObsFinder {

    data class Found(val host: String, val network: Network?)

    private class LocalNet(val address: Inet4Address, val prefix: Int, val network: Network?)

    /** Searches every local (non-mobile-data) network at once. Blocking; call off the main thread. */
    fun find(context: Context, port: Int, timeoutMs: Long = 1500): List<Found> {
        val results = mutableListOf<Found>()
        localNetworks(context).map { net ->
            thread(name = "obs-finder") {
                val targets = hostsToScan(net.address.toInt(), net.prefix).map { it.toInet4() }
                val hosts = scan(net, targets, port, timeoutMs)
                synchronized(results) { hosts.forEach { results += Found(it, net.network) } }
            }
        }.forEach { it.join() }
        return results.distinctBy { it.host }.sortedBy { InetAddress.getByName(it.host).toInt() }
    }

    /** Checks one address, e.g. the one used last time. Blocking; call off the main thread. */
    fun probe(context: Context, host: String, port: Int, timeoutMs: Long = 800): Boolean {
        val target = runCatching { InetAddress.getByName(host) }.getOrNull() as? Inet4Address ?: return false
        val net = localNetworks(context).firstOrNull { it.contains(target) }
        return runCatching {
            DatagramSocket(null).use { socket ->
                net?.let { socket.bind(InetSocketAddress(it.address, 0)) }
                net?.network?.let { runCatching { it.bindSocket(socket) } }
                val found = mutableSetOf<String>()
                val packet = inductionPacket(Random.nextInt())
                // Two tries, because Wi-Fi can drop a packet.
                for (attempt in 0 until 2) {
                    socket.send(DatagramPacket(packet, packet.size, target, port))
                    receiveUntil(socket, found, SystemClock.elapsedRealtime() + timeoutMs / 2)
                    if (found.isNotEmpty()) return@use true
                }
                false
            }
        }.getOrDefault(false)
    }

    private fun localNetworks(context: Context): List<LocalNet> {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val byInterface = HashMap<String, Network>()
        val mobile = HashSet<String>()
        @Suppress("DEPRECATION")
        for (n in cm.allNetworks) {
            val name = cm.getLinkProperties(n)?.interfaceName ?: continue
            if (cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) mobile += name
            else byInterface[name] = n
        }
        // NetworkInterface also covers USB tethering, which isn't one of Android's networks.
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull().orEmpty()
        return interfaces
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .filter { it.name !in mobile && SKIP_PREFIXES.none(it.name::startsWith) }
            .flatMap { nif ->
                nif.interfaceAddresses.mapNotNull { ia ->
                    (ia.address as? Inet4Address)?.let { LocalNet(it, ia.networkPrefixLength.toInt(), byInterface[nif.name]) }
                }
            }
    }

    /** Mobile data, VPN and placeholder interfaces never lead to the church computer. */
    private val SKIP_PREFIXES = listOf("rmnet", "ccmni", "v4-", "tun", "dummy", "ifb", "pdp")

    private fun LocalNet.contains(target: Inet4Address): Boolean {
        val mask = maskOf(prefix)
        return address.toInt() and mask == target.toInt() and mask
    }

    private fun scan(net: LocalNet, targets: List<InetAddress>, port: Int, timeoutMs: Long): Set<String> {
        val found = mutableSetOf<String>()
        runCatching {
            DatagramSocket(null).use { socket ->
                socket.bind(InetSocketAddress(net.address, 0))
                net.network?.let { runCatching { it.bindSocket(socket) } }
                val packet = inductionPacket(Random.nextInt())
                val deadline = SystemClock.elapsedRealtime() + timeoutMs
                // Two rounds, because Wi-Fi can drop a packet.
                for (round in 0 until 2) {
                    for (t in targets) runCatching { socket.send(DatagramPacket(packet, packet.size, t, port)) }
                    receiveUntil(socket, found, if (round == 0) SystemClock.elapsedRealtime() + timeoutMs / 3 else deadline)
                }
            }
        }
        return found
    }

    private fun receiveUntil(socket: DatagramSocket, found: MutableSet<String>, deadline: Long) {
        val buf = ByteArray(256)
        while (true) {
            val left = deadline - SystemClock.elapsedRealtime()
            if (left <= 0) return
            socket.soTimeout = left.toInt().coerceAtLeast(1)
            val p = DatagramPacket(buf, buf.size)
            try {
                socket.receive(p)
            } catch (e: SocketTimeoutException) {
                return
            } catch (e: IOException) {
                continue
            }
            if (isInductionReply(p.data, p.length)) p.address.hostAddress?.let(found::add)
        }
    }

    // region Pure helpers (unit tested)

    /** An SRT caller's first handshake packet (HSv4 induction), as libsrt sends it. */
    internal fun inductionPacket(socketId: Int): ByteArray = ByteBuffer.allocate(64)
        .putInt(0x80000000.toInt()) // control packet, type 0 = handshake
        .putInt(0) // type-specific info
        .putInt(0) // timestamp
        .putInt(0) // destination socket id: 0 = new connection
        .putInt(4) // handshake version
        .putShort(0) // encryption
        .putShort(2) // extension field: UDT_DGRAM
        .putInt(Random.nextInt() and 0x7FFFFFFF) // initial sequence number
        .putInt(1500) // MTU
        .putInt(8192) // flow window
        .putInt(1) // handshake type: induction
        .putInt(socketId)
        .putInt(0) // SYN cookie
        .array() // peer IP: left as zeros

    /** A listener's answer: a handshake of type induction carrying SRT's magic number 0x4A17. */
    internal fun isInductionReply(data: ByteArray, length: Int): Boolean {
        if (length < 48) return false
        val b = ByteBuffer.wrap(data, 0, length)
        return b.getInt(0) == 0x80000000.toInt() && b.getShort(22).toInt() and 0xFFFF == 0x4A17 && b.getInt(36) == 1
    }

    /** Every other address on the subnet; at most a /24 (254 hosts) so the search stays fast. */
    internal fun hostsToScan(ip: Int, prefix: Int): List<Int> {
        val p = prefix.coerceIn(24, 30)
        val mask = maskOf(p)
        val base = ip and mask
        val size = 1 shl (32 - p)
        return (1 until size - 1).map { base + it }.filter { it != ip }
    }

    private fun maskOf(prefix: Int) = if (prefix <= 0) 0 else -1 shl (32 - prefix.coerceAtMost(32))

    // endregion

    private fun InetAddress.toInt(): Int = ByteBuffer.wrap(address).int

    private fun Int.toInet4(): InetAddress = InetAddress.getByAddress(ByteBuffer.allocate(4).putInt(this).array())
}
