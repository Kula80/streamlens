package com.streamlens.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager

data class NetworkReport(
    val kind: String,
    val summary: String,
    val warning: String?,
    /** Highest bitrate this connection should comfortably carry. */
    val suggestedMbps: Int,
)

/** Looks at how the phone is connected and explains problems in plain words. */
object NetworkCheck {

    fun run(context: Context): NetworkReport {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            ?: return NetworkReport("None", "Not connected", "Connect to the same network as the OBS computer.", 0)

        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
                NetworkReport("Ethernet", "Wired connection", null, 80)

            caps.hasTransport(NetworkCapabilities.TRANSPORT_USB) ->
                NetworkReport("USB", "USB connection", null, 80)

            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> wifi(context)

            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkReport(
                "Mobile data", "Mobile data",
                "You're on mobile data. Connect to the same Wi-Fi or Ethernet as the OBS computer.", 0,
            )

            else -> NetworkReport("Other", "Connected", null, 20)
        }
    }

    @Suppress("DEPRECATION")
    private fun wifi(context: Context): NetworkReport {
        val info = context.applicationContext.getSystemService(WifiManager::class.java).connectionInfo
        val ghz = when {
            info.frequency >= 5925 -> "6 GHz"
            info.frequency >= 4900 -> "5 GHz"
            else -> "2.4 GHz"
        }
        val rssi = info.rssi
        val signal = when {
            rssi >= -55 -> "Excellent"
            rssi >= -65 -> "Good"
            rssi >= -72 -> "Fair"
            else -> "Weak"
        }
        val summary = "Wi-Fi $ghz · $signal signal ($rssi dBm) · ${info.linkSpeed} Mbps link"
        val warning = when {
            ghz == "2.4 GHz" -> "You're on 2.4 GHz. Switch to 5 GHz for better quality and fewer drops."
            signal == "Weak" -> "Weak signal here. Move closer to a router or use an Ethernet adapter."
            signal == "Fair" -> "Signal is fair. The app will lower quality if the link struggles."
            else -> null
        }
        // Roughly a third of the link rate is usable for steady video on a busy network.
        val suggested = (info.linkSpeed / 3).coerceIn(4, if (ghz == "2.4 GHz") 15 else 60)
        return NetworkReport("Wi-Fi", summary, warning, suggested)
    }
}
