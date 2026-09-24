package com.bitlockerdroid.share

data class LanShareConfig(
    val volumeGuid: String = "",
    val devicePath: String = "",
    val volumeLabel: String = "",
    val port: Int = 8080,
    val isReadOnly: Boolean = true,
    val authEnabled: Boolean = false,
    val username: String = "admin",
    val password: String = ""
)

data class LanShareState(
    val isRunning: Boolean = false,
    val volumeGuid: String = "",
    val volumeLabel: String = "",
    val devicePath: String = "",
    val port: Int = 8080,
    val isReadOnly: Boolean = true,
    val authEnabled: Boolean = false,
    val username: String = "",
    val addresses: List<LanAddress> = emptyList(),
    val activeConnections: Int = 0,
    val bytesServed: Long = 0L,
    val errorMessage: String? = null,
    val startedAt: Long = 0L
) {
    val primaryUrl: String
        get() {
            val ip = addresses.firstOrNull()?.ip ?: "127.0.0.1"
            return "http://$ip:$port"
        }
}
