package com.example.model

data class SharedFile(
    val id: String,
    val name: String,
    val size: Long,
    val mimeType: String,
    val uriString: String? = null,
    val filePath: String? = null,
    val isUploadedByPeer: Boolean = false,
    val dateAdded: Long = System.currentTimeMillis()
) {
    fun getFormattedSize(): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        val index = digitGroups.coerceIn(0, units.size - 1)
        val value = size / Math.pow(1024.0, index.toDouble())
        return String.format(java.util.Locale.US, "%.1f %s", value, units[index])
    }
}
