package com.vaultx.app.data.telegram

object ChunkedUploader {

    // Telegram Free file size limit is 2GB (2,147,483,648 bytes)
    const val TELEGRAM_MAX_FILE_BYTES: Long = 2_147_483_648L
    // Recommended chunk size is 1.5GB to avoid network timeout and boundary issues
    const val RECOMMENDED_CHUNK_BYTES: Long = 1_610_612_736L // 1.5 GB

    fun calculateChunks(totalBytes: Long): Int {
        if (totalBytes <= TELEGRAM_MAX_FILE_BYTES) return 1
        return Math.ceil(totalBytes.toDouble() / RECOMMENDED_CHUNK_BYTES).toInt()
    }

    fun generateChunkCaption(fileName: String, chunkIndex: Int, totalChunks: Int, totalBytes: Long): String {
        return if (totalChunks <= 1) {
            "VaultX_File: $fileName | Size: ${formatBytes(totalBytes)}"
        } else {
            "[VaultX_Part ${chunkIndex + 1}/$totalChunks]: $fileName | Total: ${formatBytes(totalBytes)} | Integrity: SHA256"
        }
    }

    private fun formatBytes(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        return if (gb >= 1.0) String.format("%.2f GB", gb) else "${bytes / (1024 * 1024)} MB"
    }
}
