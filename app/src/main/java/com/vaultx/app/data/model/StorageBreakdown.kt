package com.vaultx.app.data.model

data class StorageBreakdown(
    val totalBytes: Long = 0,
    val photoBytes: Long = 0,
    val photoCount: Int = 0,
    val videoBytes: Long = 0,
    val videoCount: Int = 0,
    val pdfBytes: Long = 0,
    val pdfCount: Int = 0,
    val docBytes: Long = 0,
    val docCount: Int = 0,
    val audioBytes: Long = 0,
    val audioCount: Int = 0
) {
    fun formatBytes(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format("%.2f GB", gb)
            mb >= 1.0 -> String.format("%.1f MB", mb)
            kb >= 1.0 -> String.format("%.0f KB", kb)
            else -> "$bytes B"
        }
    }

    val formattedTotal: String get() = formatBytes(totalBytes)
    val formattedPhotos: String get() = formatBytes(photoBytes)
    val formattedVideos: String get() = formatBytes(videoBytes)
    val formattedPdfs: String get() = formatBytes(pdfBytes)
    val formattedDocs: String get() = formatBytes(docBytes)
    val formattedAudio: String get() = formatBytes(audioBytes)
}
