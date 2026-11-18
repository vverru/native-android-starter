package com.vaultx.app.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.vaultx.app.VaultXApplication
import com.vaultx.app.data.model.FileCategory
import com.vaultx.app.data.model.FileItem

class BackupWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val db = VaultXApplication.instance.databaseHelper
            // Check for new DCIM or WhatsApp media to backup
            // In a production build, scan MediaStore.Images.Media and MediaStore.Video.Media
            val now = System.currentTimeMillis()
            val backupSample = FileItem(
                name = "Camera_AutoSync_${System.currentTimeMillis() % 10000}.jpg",
                sizeBytes = 4_200_000,
                mimeType = "image/jpeg",
                category = FileCategory.PHOTOS,
                isFavorite = false,
                uploadTimestamp = now
            )
            db.insertFile(backupSample)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
