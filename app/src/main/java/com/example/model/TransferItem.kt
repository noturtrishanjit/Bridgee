package com.example.model

enum class TransferDirection {
    UPLOAD,
    DOWNLOAD
}

enum class TransferStatus {
    WAITING,
    CONNECTING,
    TRANSFERRING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class TransferItem(
    val id: String,
    val fileId: String,
    val fileName: String,
    val totalBytes: Long,
    val transferredBytes: Long = 0L,
    val direction: TransferDirection,
    val status: TransferStatus,
    val speedBytesPerSec: Long = 0L,
    val peerAddress: String = "",
    val peerDeviceName: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val errorMessage: String? = null,
    val isEncrypted: Boolean = true
) {
    val progressPercentage: Int
        get() = if (totalBytes > 0) ((transferredBytes.toDouble() / totalBytes) * 100).toInt().coerceIn(0, 100) else 0

    fun getFormattedSpeed(): String {
        if (speedBytesPerSec <= 0) return "0 KB/s"
        val kb = speedBytesPerSec / 1024.0
        return if (kb >= 1024) {
            String.format(java.util.Locale.US, "%.1f MB/s", kb / 1024.0)
        } else {
            String.format(java.util.Locale.US, "%.0f KB/s", kb)
        }
    }

    fun getFormattedTransferred(): String {
        return formatSize(transferredBytes)
    }

    fun getFormattedTotal(): String {
        return formatSize(totalBytes)
    }

    fun getEstimatedRemainingSeconds(): Long {
        if (speedBytesPerSec <= 0 || totalBytes <= transferredBytes) return 0L
        val remainingBytes = totalBytes - transferredBytes
        return remainingBytes / speedBytesPerSec
    }

    fun getFormattedEta(): String {
        val seconds = getEstimatedRemainingSeconds()
        if (seconds <= 0) return ""
        val m = seconds / 60
        val s = seconds % 60
        return if (m > 0) "${m}m ${s}s left" else "${s}s left"
    }

    companion object {
        fun formatSize(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
            val index = digitGroups.coerceIn(0, units.size - 1)
            val value = bytes / Math.pow(1024.0, index.toDouble())
            return String.format(java.util.Locale.US, "%.1f %s", value, units[index])
        }
    }
}
