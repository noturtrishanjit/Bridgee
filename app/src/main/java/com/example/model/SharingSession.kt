package com.example.model

data class PeerDevice(
    val id: String,
    val ip: String,
    val userAgent: String,
    val deviceName: String,
    val avatarId: String = "cyber_falcon",
    val connectedAt: Long = System.currentTimeMillis()
)

data class SharingSession(
    val sessionId: String,
    val token: String,
    val hostIp: String,
    val port: Int,
    val isActive: Boolean,
    val connectedClientsCount: Int = 0,
    val startedAt: Long = System.currentTimeMillis(),
    val deviceName: String = "Bridge Device",
    val avatarId: String = "cyber_falcon",
    val customHostUrl: String = "",
    val useCustomHostUrl: Boolean = false,
    val isEncryptionEnabled: Boolean = true
) {
    val connectionUrl: String
        get() {
            if (useCustomHostUrl && customHostUrl.isNotBlank()) {
                val clean = customHostUrl.trim().removeSuffix("/")
                val hasQuery = clean.contains("?")
                return if (hasQuery) "$clean&token=$token" else "$clean/?token=$token"
            }
            return "http://$hostIp:$port/?token=$token"
        }

    val baseHostUrl: String
        get() {
            if (useCustomHostUrl && customHostUrl.isNotBlank()) {
                val clean = customHostUrl.trim().removeSuffix("/")
                val qIdx = clean.indexOf("?")
                return if (qIdx != -1) clean.substring(0, qIdx) else clean
            }
            return "http://$hostIp:$port"
        }
}
