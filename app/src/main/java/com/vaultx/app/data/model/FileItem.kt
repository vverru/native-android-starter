package com.vaultx.app.data.model

data class FileItem(
    val id: Long = 0,
    val name: String,
    val telegramMessageId: Long = 0,
    val sizeBytes: Long,
    val mimeType: String,
    val category: FileCategory,
    val isFavorite: Boolean = false,
    val isTrash: Boolean = false,
    val uploadTimestamp: Long = System.currentTimeMillis(),
    val localUri: String? = null,
    val chunkCount: Int = 1
) {
    val formattedSize: String
        get() {
            val kb = sizeBytes / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> String.format("%.2f GB", gb)
                mb >= 1.0 -> String.format("%.1f MB", mb)
                kb >= 1.0 -> String.format("%.0f KB", kb)
                else -> "$sizeBytes B"
            }
        }
}

enum class FileCategory {
    ALL,
    PHOTOS,
    VIDEOS,
    PDFS,
    DOCS,
    AUDIO
}
