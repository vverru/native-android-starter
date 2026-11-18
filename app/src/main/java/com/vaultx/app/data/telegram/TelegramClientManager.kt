package com.vaultx.app.data.telegram

import android.content.Context
import android.content.SharedPreferences
import com.vaultx.app.BuildConfig
import com.vaultx.app.data.model.FileCategory
import com.vaultx.app.data.model.FileItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class TelegramClientManager(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("vaultx_telegram_prefs", Context.MODE_PRIVATE)

    private val _authState = MutableStateFlow(getSavedAuthState())
    val authState: StateFlow<TelegramAuthState> = _authState

    var apiId: Int
        get() = prefs.getInt(KEY_API_ID, BuildConfig.TELEGRAM_API_ID)
        set(value) = prefs.edit().putInt(KEY_API_ID, value).apply()

    var apiHash: String
        get() = prefs.getString(KEY_API_HASH, BuildConfig.TELEGRAM_API_HASH) ?: ""
        set(value) = prefs.edit().putString(KEY_API_HASH, value).apply()

    var phoneNumber: String
        get() = prefs.getString(KEY_PHONE, "") ?: ""
        private set(value) = prefs.edit().putString(KEY_PHONE, value).apply()

    var privateChannelName: String
        get() = prefs.getString(KEY_CHANNEL_NAME, "VaultX_Private_Storage") ?: "VaultX_Private_Storage"
        private set(value) = prefs.edit().putString(KEY_CHANNEL_NAME, value).apply()

    fun isLoggedIn(): Boolean = _authState.value == TelegramAuthState.LOGGED_IN

    fun requestPhoneOtp(phone: String): Boolean {
        phoneNumber = phone
        _authState.value = TelegramAuthState.WAIT_CODE
        saveAuthState(TelegramAuthState.WAIT_CODE)
        return true
    }

    fun verifyCode(code: String): Boolean {
        if (code.isNotBlank()) {
            _authState.value = TelegramAuthState.LOGGED_IN
            saveAuthState(TelegramAuthState.LOGGED_IN)
            return true
        }
        return false
    }

    fun verify2FaPassword(password: String): Boolean {
        if (password.isNotBlank()) {
            _authState.value = TelegramAuthState.LOGGED_IN
            saveAuthState(TelegramAuthState.LOGGED_IN)
            return true
        }
        return false
    }

    fun logout() {
        prefs.edit().clear().apply()
        _authState.value = TelegramAuthState.WAIT_PHONE
    }

    fun prepareFileUpload(name: String, sizeBytes: Long, mimeType: String): FileItem {
        val category = determineCategory(name, mimeType)
        val chunkCount = ChunkedUploader.calculateChunks(sizeBytes)
        val msgId = System.currentTimeMillis()

        return FileItem(
            name = name,
            telegramMessageId = msgId,
            sizeBytes = sizeBytes,
            mimeType = mimeType,
            category = category,
            isFavorite = false,
            isTrash = false,
            uploadTimestamp = System.currentTimeMillis(),
            chunkCount = chunkCount
        )
    }

    private fun determineCategory(name: String, mimeType: String): FileCategory {
        val lower = name.lowercase()
        return when {
            mimeType.startsWith("image") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp") -> FileCategory.PHOTOS
            mimeType.startsWith("video") || lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".mov") || lower.endsWith(".avi") -> FileCategory.VIDEOS
            mimeType == "application/pdf" || lower.endsWith(".pdf") -> FileCategory.PDFS
            mimeType.startsWith("audio") || lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".wav") || lower.endsWith(".aac") -> FileCategory.AUDIO
            lower.endsWith(".doc") || lower.endsWith(".docx") || lower.endsWith(".xls") || lower.endsWith(".xlsx") || lower.endsWith(".ppt") || lower.endsWith(".pptx") || lower.endsWith(".txt") -> FileCategory.DOCS
            else -> FileCategory.DOCS
        }
    }

    private fun saveAuthState(state: TelegramAuthState) {
        prefs.edit().putString(KEY_AUTH_STATE, state.name).apply()
    }

    private fun getSavedAuthState(): TelegramAuthState {
        val saved = prefs.getString(KEY_AUTH_STATE, TelegramAuthState.LOGGED_IN.name)
        return try {
            TelegramAuthState.valueOf(saved ?: TelegramAuthState.LOGGED_IN.name)
        } catch (e: Exception) {
            TelegramAuthState.LOGGED_IN
        }
    }

    companion object {
        private const val KEY_API_ID = "pref_api_id"
        private const val KEY_API_HASH = "pref_api_hash"
        private const val KEY_PHONE = "pref_phone"
        private const val KEY_CHANNEL_NAME = "pref_channel_name"
        private const val KEY_AUTH_STATE = "pref_auth_state"
    }
}

enum class TelegramAuthState {
    WAIT_PHONE,
    WAIT_CODE,
    WAIT_2FA,
    LOGGED_IN
}
