package com.streamlens.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * Keeps the stream on the network that reaches OBS (so Android can't move it to mobile data), and
 * follows that network when it goes away and comes back.
 *
 * Android gives a Wi-Fi that drops and returns a brand-new [Network]. Staying bound to the old one would
 * leave every reconnect attempt going nowhere, so: unbind when it's lost, bind again to whichever new
 * Wi-Fi/Ethernet network has the OBS computer on its local subnet.
 */
class ObsNetworkPin(context: Context) {

    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private var host: String? = null
    private var bound: Network? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Synchronized
    fun start(obsHost: String) {
        stop()
        host = obsHost
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = consider(network, lp)

            override fun onLost(network: Network) = lost(network)
        }
        callback = cb
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        // Called straight away for networks already up, then again as they change.
        cm.registerNetworkCallback(request, cb)
    }

    @Synchronized
    fun stop() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        bound = null
        host = null
        cm.bindProcessToNetwork(null)
    }

    @Synchronized
    private fun consider(network: Network, lp: LinkProperties) {
        val target = host?.let(::ipv4) ?: return
        if (network == bound) return
        val reachesObs = lp.linkAddresses.any { la ->
            val a = la.address as? Inet4Address ?: return@any false
            sameSubnet(a.toInt(), la.prefixLength, target)
        }
        if (reachesObs && cm.bindProcessToNetwork(network)) bound = network
    }

    @Synchronized
    private fun lost(network: Network) {
        if (network != bound) return
        bound = null
        // Let reconnect attempts use whatever is available until the Wi-Fi is back.
        cm.bindProcessToNetwork(null)
    }

    companion object {
        private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

        /** Only literal addresses: no DNS lookups from a network callback. */
        internal fun ipv4(host: String): Int? =
            if (IPV4.matches(host)) (InetAddress.getByName(host) as? Inet4Address)?.toInt() else null

        internal fun sameSubnet(address: Int, prefix: Int, target: Int): Boolean {
            if (prefix !in 1..32) return false
            val mask = -1 shl (32 - prefix)
            return address and mask == target and mask
        }

        private fun InetAddress.toInt(): Int = ByteBuffer.wrap(address).int
    }
}
