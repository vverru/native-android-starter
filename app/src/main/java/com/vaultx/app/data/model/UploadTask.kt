package com.vaultx.app.data.model

data class UploadTask(
    val id: String,
    val fileName: String,
    val sizeBytes: Long,
    var uploadedBytes: Long = 0,
    var isPaused: Boolean = false,
    var status: UploadStatus = UploadStatus.UPLOADING
) {
    val progressPercent: Int
        get() = if (sizeBytes > 0) ((uploadedBytes.toDouble() / sizeBytes) * 100).toInt() else 0

    val formattedSpeed: String
        get() = "3.2 MB/s"
}

enum class UploadStatus {
    UPLOADING,
    PAUSED,
    COMPLETED,
    FAILED
}
