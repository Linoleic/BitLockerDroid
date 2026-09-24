package com.bitlockerdroid.share

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

data class LanAddress(
    val interfaceName: String,
    val typeLabel: String,
    val ip: String
)

object NetworkUtils {

    /**
     * Enumerates all active IPv4 network addresses available on local LAN interfaces
     * (e.g. Wi-Fi, Mobile Hotspot AP, Ethernet, USB tethering).
     */
    fun getAvailableLanAddresses(): List<LanAddress> {
        val result = mutableListOf<LanAddress>()
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback || intf.isPointToPoint) continue

                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        val hostAddress = addr.hostAddress ?: continue
                        val name = intf.name.lowercase()
                        val typeLabel = when {
                            name.startsWith("wlan") -> "Wi-Fi (${intf.name})"
                            name.startsWith("ap") || name.startsWith("softap") || name.startsWith("swlan") -> "Hotspot (${intf.name})"
                            name.startsWith("eth") -> "Ethernet (${intf.name})"
                            name.startsWith("rndis") || name.startsWith("usb") -> "USB Tethering (${intf.name})"
                            else -> "Interface (${intf.name})"
                        }
                        result.add(LanAddress(intf.name, typeLabel, hostAddress))
                    }
                }
            }
        } catch (_: Exception) {}

        // Prioritize Wi-Fi and Hotspot over other interfaces
        return result.sortedBy { addr ->
            val n = addr.interfaceName.lowercase()
            when {
                n.startsWith("wlan") -> 0
                n.startsWith("ap") || n.startsWith("softap") || n.startsWith("swlan") -> 1
                n.startsWith("eth") -> 2
                n.startsWith("rndis") || n.startsWith("usb") -> 3
                else -> 4
            }
        }
    }

    /**
     * Returns the primary IPv4 address or fallback to "127.0.0.1".
     */
    fun getPrimaryIp(): String {
        return getAvailableLanAddresses().firstOrNull()?.ip ?: "127.0.0.1"
    }

    /**
     * Checks if device is currently connected to Wi-Fi.
     */
    fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
