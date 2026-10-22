package com.example.server

import android.content.Context
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.Collections
import java.util.Locale

object NetworkUtils {

    data class NetworkInfo(
        val ipAddress: String,
        val interfaceName: String,
        val isHotspot: Boolean,
        val isWifi: Boolean,
        val displayName: String = ""
    )

    /**
     * Resolves all available local IPv4 addresses on device interfaces.
     */
    fun getAllAvailableIps(context: Context): List<NetworkInfo> {
        val result = mutableListOf<NetworkInfo>()

        // 1. Try WifiManager connectionInfo
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ipInt = wifiManager?.connectionInfo?.ipAddress ?: 0
            if (ipInt != 0) {
                val wifiIp = String.format(
                    Locale.US,
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    (ipInt shr 8) and 0xff,
                    (ipInt shr 16) and 0xff,
                    (ipInt shr 24) and 0xff
                )
                if (isValidLocalIpv4(wifiIp)) {
                    result.add(
                        NetworkInfo(
                            ipAddress = wifiIp,
                            interfaceName = "wlan0",
                            isHotspot = false,
                            isWifi = true,
                            displayName = "Wi-Fi ($wifiIp)"
                        )
                    )
                }
            }
        } catch (ignored: Exception) {}

        // 2. Iterate network interfaces
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (nif in interfaces) {
                var isUp = false
                try {
                    isUp = nif.isUp
                } catch (e: Exception) {
                    isUp = true // Some virtual hotspot interfaces throw on isUp check
                }

                if (!nif.isLoopback) {
                    val addresses = Collections.list(nif.inetAddresses)
                    for (addr in addresses) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            val host = addr.hostAddress ?: continue
                            if (isValidLocalIpv4(host)) {
                                val name = nif.name.lowercase(Locale.US)
                                val isAp = name.startsWith("ap") || name.startsWith("softap") ||
                                        name.startsWith("swlan") || name.contains("tether") ||
                                        host.startsWith("192.168.43.") || host.startsWith("192.168.44.")
                                val isWifi = name.startsWith("wlan") && !isAp

                                val label = when {
                                    isAp -> "Mobile Hotspot ($host)"
                                    isWifi -> "Wi-Fi ($host)"
                                    name.startsWith("eth") -> "Ethernet ($host)"
                                    name.startsWith("rndis") -> "USB Tethering ($host)"
                                    else -> "${nif.name} ($host)"
                                }

                                if (result.none { it.ipAddress == host }) {
                                    result.add(
                                        NetworkInfo(
                                            ipAddress = host,
                                            interfaceName = nif.name,
                                            isHotspot = isAp,
                                            isWifi = isWifi,
                                            displayName = label
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return result
    }

    /**
     * Resolves the best candidate local IPv4 address to host the Bridge HTTP server.
     */
    fun getLocalIpAddress(context: Context): NetworkInfo {
        val all = getAllAvailableIps(context)

        // Priority 1: Hotspot (e.g. 192.168.43.1)
        all.firstOrNull { it.isHotspot }?.let { return it }

        // Priority 2: Wi-Fi interface
        all.firstOrNull { it.isWifi }?.let { return it }

        // Priority 3: Any private IPv4 LAN (192.168.x.x, 10.x.x.x, 172.16-31.x.x)
        all.firstOrNull { isPrivateLan(it.ipAddress) }?.let { return it }

        // Priority 4: Any non-loopback
        all.firstOrNull()?.let { return it }

        // Fallback to localhost
        return NetworkInfo(
            ipAddress = "127.0.0.1",
            interfaceName = "lo",
            isHotspot = false,
            isWifi = false,
            displayName = "Local Loopback (No Wi-Fi/Hotspot)"
        )
    }

    private fun isValidLocalIpv4(ip: String): Boolean {
        if (ip.isEmpty() || ip == "127.0.0.1" || ip == "0.0.0.0") return false
        if (ip.startsWith("127.")) return false
        if (ip.startsWith("169.254.")) return false // Link-local unassigned
        return true
    }

    private fun isPrivateLan(ip: String): Boolean {
        return ip.startsWith("192.168.") || ip.startsWith("10.") ||
                (ip.startsWith("172.") && try {
                    val second = ip.split(".")[1].toInt()
                    second in 16..31
                } catch (e: Exception) { false })
    }

    /**
     * Finds an available port starting from preferredPort up to preferredPort + 50.
     */
    fun findAvailablePort(preferredPort: Int = 8080): Int {
        for (port in preferredPort..(preferredPort + 50)) {
            try {
                val serverSocket = ServerSocket()
                serverSocket.reuseAddress = true
                serverSocket.bind(java.net.InetSocketAddress("0.0.0.0", port))
                val localPort = serverSocket.localPort
                serverSocket.close()
                return localPort
            } catch (e: Exception) {
                // Port occupied, try next
            }
        }
        // Fallback to OS assigned random free port
        val socket = ServerSocket(0)
        val port = socket.localPort
        socket.close()
        return port
    }

    fun isEmulator(): Boolean {
        val fp = android.os.Build.FINGERPRINT.lowercase()
        val model = android.os.Build.MODEL.lowercase()
        val hardware = android.os.Build.HARDWARE.lowercase()
        return fp.startsWith("generic") || fp.contains("emulator") ||
                model.contains("google_sdk") || model.contains("emulator") ||
                hardware.contains("goldfish") || hardware.contains("ranchu")
    }
}
