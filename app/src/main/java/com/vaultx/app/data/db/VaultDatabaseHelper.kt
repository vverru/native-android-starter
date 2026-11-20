package com.vaultx.app.data.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.vaultx.app.data.model.FileCategory
import com.vaultx.app.data.model.FileItem
import com.vaultx.app.data.model.StorageBreakdown

class VaultDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_FILES (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_NAME TEXT NOT NULL,
                $COL_TELEGRAM_MSG_ID INTEGER DEFAULT 0,
                $COL_SIZE_BYTES INTEGER NOT NULL,
                $COL_MIME_TYPE TEXT NOT NULL,
                $COL_CATEGORY TEXT NOT NULL,
                $COL_IS_FAVORITE INTEGER DEFAULT 0,
                $COL_IS_TRASH INTEGER DEFAULT 0,
                $COL_TIMESTAMP INTEGER NOT NULL,
                $COL_LOCAL_URI TEXT,
                $COL_THUMBNAIL_URI TEXT,
                $COL_CHUNK_COUNT INTEGER DEFAULT 1
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE $TABLE_FILES ADD COLUMN $COL_THUMBNAIL_URI TEXT")
            } catch (_: Exception) {}
        }
    }

    fun hasFileWithTelegramId(telegramMsgId: Long): Boolean {
        if (telegramMsgId <= 0L) return false
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT 1 FROM $TABLE_FILES WHERE $COL_TELEGRAM_MSG_ID = ? LIMIT 1",
            arrayOf(telegramMsgId.toString())
        )
        return cursor.use { it.moveToFirst() }
    }

    fun updateTelegramMessageId(oldMsgId: Long, newMsgId: Long): Int {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_TELEGRAM_MSG_ID, newMsgId)
        }
        return db.update(TABLE_FILES, values, "$COL_TELEGRAM_MSG_ID = ?", arrayOf(oldMsgId.toString()))
    }

    fun updateLocalUri(fileId: Long, localUri: String): Int {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_LOCAL_URI, localUri)
        }
        return db.update(TABLE_FILES, values, "$COL_ID = ?", arrayOf(fileId.toString()))
    }

    fun updateThumbnailUri(fileId: Long, thumbUri: String): Int {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_THUMBNAIL_URI, thumbUri)
        }
        return db.update(TABLE_FILES, values, "$COL_ID = ?", arrayOf(fileId.toString()))
    }

    fun updateThumbnailUriByTelegramMsgId(telegramMsgId: Long, thumbUri: String): Int {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_THUMBNAIL_URI, thumbUri)
        }
        return db.update(TABLE_FILES, values, "$COL_TELEGRAM_MSG_ID = ?", arrayOf(telegramMsgId.toString()))
    }

    fun updateLocalUriByTelegramMsgId(telegramMsgId: Long, localUri: String): Int {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_LOCAL_URI, localUri)
        }
        return db.update(TABLE_FILES, values, "$COL_TELEGRAM_MSG_ID = ?", arrayOf(telegramMsgId.toString()))
    }

    fun findFileByName(name: String): FileItem? {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_FILES,
            null,
            "$COL_NAME = ? AND $COL_IS_TRASH = 0",
            arrayOf(name),
            null,
            null,
            "$COL_TIMESTAMP DESC",
            "1"
        )
        val list = cursorToList(cursor)
        return list.firstOrNull()
    }

    fun insertFile(item: FileItem): Long {
        val db = writableDatabase
        // 1. Check by telegramMessageId if already exists
        if (item.telegramMessageId > 0 && hasFileWithTelegramId(item.telegramMessageId)) {
            val values = ContentValues()
            if (!item.localUri.isNullOrBlank()) {
                values.put(COL_LOCAL_URI, item.localUri)
            }
            if (!item.thumbnailUri.isNullOrBlank()) {
                values.put(COL_THUMBNAIL_URI, item.thumbnailUri)
            }
            if (values.size() > 0) {
                db.update(TABLE_FILES, values, "$COL_TELEGRAM_MSG_ID = ?", arrayOf(item.telegramMessageId.toString()))
            }
            return -1L
        }

        // 2. Check if file with same name already uploaded locally (prevent duplicate row with null localUri on sync)
        val existing = findFileByName(item.name)
        if (existing != null) {
            val values = ContentValues()
            if (item.telegramMessageId > 0 && (existing.telegramMessageId == 0L || existing.telegramMessageId > 1_000_000_000L)) {
                values.put(COL_TELEGRAM_MSG_ID, item.telegramMessageId)
            }
            if (!item.localUri.isNullOrBlank()) {
                values.put(COL_LOCAL_URI, item.localUri)
            }
            if (!item.thumbnailUri.isNullOrBlank()) {
                values.put(COL_THUMBNAIL_URI, item.thumbnailUri)
            }
            if (values.size() > 0) {
                db.update(TABLE_FILES, values, "$COL_ID = ?", arrayOf(existing.id.toString()))
            }
            return existing.id
        }

        val values = ContentValues().apply {
            put(COL_NAME, item.name)
            put(COL_TELEGRAM_MSG_ID, item.telegramMessageId)
            put(COL_SIZE_BYTES, item.sizeBytes)
            put(COL_MIME_TYPE, item.mimeType)
            put(COL_CATEGORY, item.category.name)
            put(COL_IS_FAVORITE, if (item.isFavorite) 1 else 0)
            put(COL_IS_TRASH, if (item.isTrash) 1 else 0)
            put(COL_TIMESTAMP, item.uploadTimestamp)
            put(COL_LOCAL_URI, item.localUri)
            put(COL_THUMBNAIL_URI, item.thumbnailUri)
            put(COL_CHUNK_COUNT, item.chunkCount)
        }
        return db.insert(TABLE_FILES, null, values)
    }

    fun getAllFiles(category: FileCategory = FileCategory.ALL, searchQuery: String = "", sortBy: String = "date_desc"): List<FileItem> {
        val db = readableDatabase
        val selectionList = mutableListOf("$COL_IS_TRASH = 0")
        val selectionArgs = mutableListOf<String>()

        if (category != FileCategory.ALL) {
            selectionList.add("$COL_CATEGORY = ?")
            selectionArgs.add(category.name)
        }

        if (searchQuery.isNotBlank()) {
            selectionList.add("$COL_NAME LIKE ?")
            selectionArgs.add("%$searchQuery%")
        }

        val orderBy = when (sortBy) {
            "name_asc" -> "$COL_NAME COLLATE NOCASE ASC"
            "size_desc" -> "$COL_SIZE_BYTES DESC"
            "size_asc" -> "$COL_SIZE_BYTES ASC"
            else -> "$COL_TIMESTAMP DESC"
        }

        val cursor = db.query(
            TABLE_FILES,
            null,
            selectionList.joinToString(" AND "),
            selectionArgs.toTypedArray(),
            null,
            null,
            orderBy
        )

        return cursorToList(cursor)
    }

    fun getRecentFiles(limit: Int = 10): List<FileItem> {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_FILES,
            null,
            "$COL_IS_TRASH = 0",
            null,
            null,
            null,
            "$COL_TIMESTAMP DESC",
            limit.toString()
        )
        return cursorToList(cursor)
    }

    fun getFavorites(category: FileCategory = FileCategory.ALL): List<FileItem> {
        val db = readableDatabase
        val selection = if (category == FileCategory.ALL) {
            "$COL_IS_FAVORITE = 1 AND $COL_IS_TRASH = 0"
        } else {
            "$COL_IS_FAVORITE = 1 AND $COL_IS_TRASH = 0 AND $COL_CATEGORY = '${category.name}'"
        }

        val cursor = db.query(
            TABLE_FILES,
            null,
            selection,
            null,
            null,
            null,
            "$COL_TIMESTAMP DESC"
        )
        return cursorToList(cursor)
    }

    fun getTrashFiles(): List<FileItem> {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_FILES,
            null,
            "$COL_IS_TRASH = 1",
            null,
            null,
            null,
            "$COL_TIMESTAMP DESC"
        )
        return cursorToList(cursor)
    }

    fun setFavorite(fileId: Long, isFavorite: Boolean): Int {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_IS_FAVORITE, if (isFavorite) 1 else 0)
        }
        return db.update(TABLE_FILES, values, "$COL_ID = ?", arrayOf(fileId.toString()))
    }

    fun moveToTrash(fileId: Long): Int {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_IS_TRASH, 1)
        }
        return db.update(TABLE_FILES, values, "$COL_ID = ?", arrayOf(fileId.toString()))
    }

    fun restoreFromTrash(fileId: Long): Int {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_IS_TRASH, 0)
        }
        return db.update(TABLE_FILES, values, "$COL_ID = ?", arrayOf(fileId.toString()))
    }

    fun deletePermanently(fileId: Long): Int {
        val db = writableDatabase
        return db.delete(TABLE_FILES, "$COL_ID = ?", arrayOf(fileId.toString()))
    }

    private fun cursorToList(cursor: Cursor): List<FileItem> {
        val list = mutableListOf<FileItem>()
        cursor.use {
            while (it.moveToNext()) {
                val thumbColIndex = it.getColumnIndex(COL_THUMBNAIL_URI)
                val thumbUri = if (thumbColIndex != -1) it.getString(thumbColIndex) else null

                val item = FileItem(
                    id = it.getLong(it.getColumnIndexOrThrow(COL_ID)),
                    name = it.getString(it.getColumnIndexOrThrow(COL_NAME)),
                    telegramMessageId = it.getLong(it.getColumnIndexOrThrow(COL_TELEGRAM_MSG_ID)),
                    sizeBytes = it.getLong(it.getColumnIndexOrThrow(COL_SIZE_BYTES)),
                    mimeType = it.getString(it.getColumnIndexOrThrow(COL_MIME_TYPE)),
                    category = try {
                        FileCategory.valueOf(it.getString(it.getColumnIndexOrThrow(COL_CATEGORY)))
                    } catch (e: Exception) {
                        FileCategory.ALL
                    },
                    isFavorite = it.getInt(it.getColumnIndexOrThrow(COL_IS_FAVORITE)) == 1,
                    isTrash = it.getInt(it.getColumnIndexOrThrow(COL_IS_TRASH)) == 1,
                    uploadTimestamp = it.getLong(it.getColumnIndexOrThrow(COL_TIMESTAMP)),
                    localUri = it.getString(it.getColumnIndexOrThrow(COL_LOCAL_URI)),
                    thumbnailUri = thumbUri,
                    chunkCount = it.getInt(it.getColumnIndexOrThrow(COL_CHUNK_COUNT))
                )
                list.add(item)
            }
        }
        return list
    }

    fun renameFile(fileId: Long, newName: String, newLocalUri: String? = null): Int {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_NAME, newName)
            if (newLocalUri != null) {
                put(COL_LOCAL_URI, newLocalUri)
            }
        }
        return db.update(TABLE_FILES, values, "$COL_ID = ?", arrayOf(fileId.toString()))
    }

    fun getStorageBreakdown(): StorageBreakdown {
        val db = readableDatabase
        var totalBytes = 0L
        var photoBytes = 0L
        var photoCount = 0
        var videoBytes = 0L
        var videoCount = 0
        var pdfBytes = 0L
        var pdfCount = 0
        var docBytes = 0L
        var docCount = 0
        var audioBytes = 0L
        var audioCount = 0

        val cursor = db.rawQuery(
            "SELECT $COL_CATEGORY, SUM($COL_SIZE_BYTES), COUNT(*) FROM $TABLE_FILES WHERE $COL_IS_TRASH = 0 GROUP BY $COL_CATEGORY",
            null
        )
        cursor.use {
            while (it.moveToNext()) {
                val catStr = it.getString(0)
                val sumBytes = it.getLong(1)
                val count = it.getInt(2)
                totalBytes += sumBytes

                when (catStr) {
                    FileCategory.PHOTOS.name -> {
                        photoBytes = sumBytes
                        photoCount = count
                    }
                    FileCategory.VIDEOS.name -> {
                        videoBytes = sumBytes
                        videoCount = count
                    }
                    FileCategory.PDFS.name -> {
                        pdfBytes = sumBytes
                        pdfCount = count
                    }
                    FileCategory.DOCS.name -> {
                        docBytes = sumBytes
                        docCount = count
                    }
                    FileCategory.AUDIO.name -> {
                        audioBytes = sumBytes
                        audioCount = count
                    }
                }
            }
        }

        return StorageBreakdown(
            totalBytes = totalBytes,
            photoBytes = photoBytes,
            photoCount = photoCount,
            videoBytes = videoBytes,
            videoCount = videoCount,
            pdfBytes = pdfBytes,
            pdfCount = pdfCount,
            docBytes = docBytes,
            docCount = docCount,
            audioBytes = audioBytes,
            audioCount = audioCount
        )
    }

    fun purgeDummyData() {
        val db = writableDatabase
        // Remove old dummy sample records and orphan placeholders completely
        db.delete(
            TABLE_FILES,
            """
            $COL_NAME IN (?, ?, ?, ?, ?) 
            OR $COL_NAME LIKE 'Document_%' 
            OR ($COL_NAME LIKE 'Photo_%' AND $COL_LOCAL_URI IS NULL) 
            OR ($COL_NAME LIKE 'Video_%' AND $COL_LOCAL_URI IS NULL) 
            OR ($COL_NAME LIKE 'Audio_%' AND $COL_LOCAL_URI IS NULL)
            OR ($COL_LOCAL_URI IS NULL AND $COL_TELEGRAM_MSG_ID = 0)
            """.trimIndent(),
            arrayOf(
                "Kashmir_Trek_2026.jpg",
                "Interstellar_4K_HDR.mkv",
                "Financial_Plan_2026_Q4.pdf",
                "Thesis_Draft_Final.docx",
                "Podcast_Episode_42.mp3"
            )
        )
    }

    companion object {
        private const val DATABASE_NAME = "vaultx.db"
        private const val DATABASE_VERSION = 2

        const val TABLE_FILES = "files"
        const val COL_ID = "id"
        const val COL_NAME = "name"
        const val COL_TELEGRAM_MSG_ID = "telegram_msg_id"
        const val COL_SIZE_BYTES = "size_bytes"
        const val COL_MIME_TYPE = "mime_type"
        const val COL_CATEGORY = "category"
        const val COL_IS_FAVORITE = "is_favorite"
        const val COL_IS_TRASH = "is_trash"
        const val COL_TIMESTAMP = "timestamp"
        const val COL_LOCAL_URI = "local_uri"
        const val COL_THUMBNAIL_URI = "thumbnail_uri"
        const val COL_CHUNK_COUNT = "chunk_count"
    }
}
