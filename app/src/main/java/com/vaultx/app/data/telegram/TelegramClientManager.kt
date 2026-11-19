package com.vaultx.app.data.telegram

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.vaultx.app.VaultXApplication
import com.vaultx.app.data.model.FileCategory
import com.vaultx.app.data.model.FileItem
import com.vaultx.app.data.security.SecurityVault
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File

/**
 * TelegramClientManager:
 * Direct, production-grade MTProto client using the official Telegram Database Library (TDLib).
 * Communicates directly with official Telegram servers over MTProto.
 *
 * API_ID and API_HASH are resolved dynamically in RAM via SecurityVault and are never
 * stored or traced as plaintext in the APK bytecode.
 */
class TelegramClientManager(private val context: Context) : Client.ResultHandler {

    private val tag = "TelegramClientManager"
    private val mainHandler = Handler(Looper.getMainLooper())

    private var tdClient: Client? = null

    private val _authState = MutableStateFlow(TelegramAuthState.WAIT_PHONE)
    val authState: StateFlow<TelegramAuthState> = _authState

    private val prefs by lazy {
        context.getSharedPreferences("vaultx_telegram_channel", Context.MODE_PRIVATE)
    }

    var phoneNumber: String = ""
        private set

    var privateChannelId: Long = 0L
        private set

    var privateChannelTitle: String = "VaultX Private Storage"
        private set

    private var isSettingUpChannel = false
    private val syncListeners = mutableListOf<() -> Unit>()

    init {
        // Restore cached channel if previously found
        privateChannelId = prefs.getLong("channel_id", 0L)
        privateChannelTitle = prefs.getString("channel_title", "VaultX Private Storage") ?: "VaultX Private Storage"

        // Initialize TDLib native client
        try {
            // Set TDLib logging level (1 = fatal errors only to keep logs clean and secure)
            Client.execute(TdApi.SetLogVerbosityLevel(1))
            tdClient = Client.create(this, null, null)
            Log.d(tag, "TDLib MTProto client created successfully")
        } catch (e: Throwable) {
            Log.e(tag, "Failed to initialize TDLib client", e)
        }
    }

    fun addSyncListener(listener: () -> Unit) {
        synchronized(syncListeners) {
            if (!syncListeners.contains(listener)) syncListeners.add(listener)
        }
    }

    fun removeSyncListener(listener: () -> Unit) {
        synchronized(syncListeners) {
            syncListeners.remove(listener)
        }
    }

    private fun notifySyncListeners() {
        mainHandler.post {
            synchronized(syncListeners) {
                syncListeners.forEach {
                    try { it.invoke() } catch (_: Exception) {}
                }
            }
        }
    }

    private fun saveChannelPreference(channelId: Long, title: String) {
        privateChannelId = channelId
        privateChannelTitle = title
        prefs.edit().putLong("channel_id", channelId).putString("channel_title", title).apply()
    }

    override fun onResult(obj: TdApi.Object?) {
        if (obj == null) return

        when (obj.constructor) {
            TdApi.UpdateAuthorizationState.CONSTRUCTOR -> {
                val update = obj as TdApi.UpdateAuthorizationState
                handleAuthState(update.authorizationState)
            }
            TdApi.UpdateNewChat.CONSTRUCTOR -> {
                val newChat = obj as TdApi.UpdateNewChat
                if (newChat.chat.title.contains("VaultX", ignoreCase = true)) {
                    if (privateChannelId == 0L) {
                        saveChannelPreference(newChat.chat.id, newChat.chat.title)
                        Log.d(tag, "Found existing VaultX private storage channel ID: $privateChannelId")
                    }
                    syncChannelFiles(newChat.chat.id)
                }
            }
            TdApi.Chats.CONSTRUCTOR -> {
                val chats = obj as TdApi.Chats
                for (chatId in chats.chatIds) {
                    tdClient?.send(TdApi.GetChat(chatId), this)
                }
            }
            TdApi.Chat.CONSTRUCTOR -> {
                val chat = obj as TdApi.Chat
                if (chat.title.contains("VaultX", ignoreCase = true)) {
                    if (privateChannelId == 0L) {
                        saveChannelPreference(chat.id, chat.title)
                        Log.d(tag, "Resolved VaultX private storage channel: ${chat.title} ($privateChannelId)")
                    }
                    syncChannelFiles(chat.id)
                }
            }
            TdApi.UpdateFile.CONSTRUCTOR -> {
                val updateFile = obj as TdApi.UpdateFile
                handleFileUpdate(updateFile.file)
            }
            TdApi.Error.CONSTRUCTOR -> {
                val error = obj as TdApi.Error
                Log.e(tag, "TDLib Server Error [${error.code}]: ${error.message}")
            }
        }
    }

    private fun handleAuthState(state: TdApi.AuthorizationState) {
        when (state.constructor) {
            TdApi.AuthorizationStateWaitTdlibParameters.CONSTRUCTOR -> {
                Log.d(tag, "TDLib requesting parameters. Applying secure obfuscated credentials...")
                val dbDir = File(context.filesDir, "tdlib_data").apply { mkdirs() }.absolutePath
                val filesDir = File(context.filesDir, "tdlib_files").apply { mkdirs() }.absolutePath

                val params = TdApi.SetTdlibParameters().apply {
                    this.useTestDc = false
                    this.databaseDirectory = dbDir
                    this.filesDirectory = filesDir
                    this.useFileDatabase = true
                    this.useChatInfoDatabase = true
                    this.useMessageDatabase = true
                    this.useSecretChats = false
                    // Secure dynamically reconstructed credentials:
                    this.apiId = SecurityVault.resolveApiId()
                    this.apiHash = SecurityVault.resolveApiHash()
                    this.systemLanguageCode = "en"
                    this.deviceModel = Build.MODEL
                    this.systemVersion = Build.VERSION.RELEASE
                    this.applicationVersion = "1.0.0"
                    this.databaseEncryptionKey = byteArrayOf()
                }

                tdClient?.send(params, this)
            }
            TdApi.AuthorizationStateWaitPhoneNumber.CONSTRUCTOR -> {
                postState(TelegramAuthState.WAIT_PHONE)
            }
            TdApi.AuthorizationStateWaitCode.CONSTRUCTOR -> {
                postState(TelegramAuthState.WAIT_CODE)
            }
            TdApi.AuthorizationStateWaitPassword.CONSTRUCTOR -> {
                postState(TelegramAuthState.WAIT_2FA)
            }
            TdApi.AuthorizationStateReady.CONSTRUCTOR -> {
                postState(TelegramAuthState.LOGGED_IN)
                setupVaultStorageChannel()
            }
            TdApi.AuthorizationStateLoggingOut.CONSTRUCTOR,
            TdApi.AuthorizationStateClosed.CONSTRUCTOR -> {
                postState(TelegramAuthState.WAIT_PHONE)
            }
        }
    }

    private fun postState(state: TelegramAuthState) {
        mainHandler.post {
            _authState.value = state
        }
    }

    fun isLoggedIn(): Boolean = _authState.value == TelegramAuthState.LOGGED_IN

    /**
     * Send real OTP request to Telegram server for the given phone number.
     */
    fun requestPhoneOtp(phone: String, onComplete: ((Boolean, String?) -> Unit)? = null) {
        this.phoneNumber = phone
        val authSettings = TdApi.PhoneNumberAuthenticationSettings().apply {
            allowFlashCall = false
            isCurrentPhoneNumber = false
            allowSmsRetrieverApi = true
        }

        tdClient?.send(TdApi.SetAuthenticationPhoneNumber(phone, authSettings)) { result ->
            mainHandler.post {
                if (result is TdApi.Ok) {
                    _authState.value = TelegramAuthState.WAIT_CODE
                    onComplete?.invoke(true, null)
                } else if (result is TdApi.Error) {
                    onComplete?.invoke(false, result.message)
                }
            }
        }
    }

    /**
     * Verify the Telegram OTP code received by the user via SMS or Telegram App.
     */
    fun verifyCode(code: String, onComplete: ((Boolean, String?) -> Unit)? = null) {
        tdClient?.send(TdApi.CheckAuthenticationCode(code)) { result ->
            mainHandler.post {
                if (result is TdApi.Ok) {
                    _authState.value = TelegramAuthState.LOGGED_IN
                    setupVaultStorageChannel()
                    onComplete?.invoke(true, null)
                } else if (result is TdApi.Error) {
                    onComplete?.invoke(false, result.message)
                }
            }
        }
    }

    /**
     * Verify Telegram Cloud 2FA Password if enabled on user's account.
     */
    fun verify2FaPassword(password: String, onComplete: ((Boolean, String?) -> Unit)? = null) {
        tdClient?.send(TdApi.CheckAuthenticationPassword(password)) { result ->
            mainHandler.post {
                if (result is TdApi.Ok) {
                    _authState.value = TelegramAuthState.LOGGED_IN
                    setupVaultStorageChannel()
                    onComplete?.invoke(true, null)
                } else if (result is TdApi.Error) {
                    onComplete?.invoke(false, result.message)
                }
            }
        }
    }

    /**
     * Ensures the private storage channel is discovered or created, and automatically
     * synchronizes all files from Telegram cloud into local SQLite.
     */
    fun ensureVaultChannelAndSync(onFinished: (() -> Unit)? = null) {
        if (privateChannelId != 0L) {
            syncChannelFiles(privateChannelId, 0L, onFinished)
        } else {
            setupVaultStorageChannel {
                syncChannelFiles(it, 0L, onFinished)
            }
        }
    }

    /**
     * Finds or creates a dedicated, private Telegram channel owned by the user.
     * Prevents creating duplicate channels on app reinstallation!
     */
    fun setupVaultStorageChannel(onReady: ((Long) -> Unit)? = null) {
        if (privateChannelId != 0L) {
            syncChannelFiles(privateChannelId)
            onReady?.invoke(privateChannelId)
            return
        }

        if (isSettingUpChannel) return
        isSettingUpChannel = true

        // 1. Search directly on Telegram server for chats with "VaultX" in title
        tdClient?.send(TdApi.SearchChatsOnServer("VaultX", 50)) { searchResult ->
            if (searchResult is TdApi.Chats && searchResult.chatIds.isNotEmpty()) {
                inspectCandidateChats(searchResult.chatIds.toList(), onReady)
            } else {
                // Preload chat list and inspect
                tdClient?.send(TdApi.LoadChats(null, 100)) { _ ->
                    tdClient?.send(TdApi.GetChats(null, 100)) { getResult ->
                        if (getResult is TdApi.Chats && getResult.chatIds.isNotEmpty()) {
                            inspectCandidateChats(getResult.chatIds.toList(), onReady)
                        } else {
                            // Truly no channel exists yet -> create a new one
                            createPrivateStorageChannel(onReady)
                        }
                    }
                }
            }
        }
    }

    private fun inspectCandidateChats(chatIds: List<Long>, onReady: ((Long) -> Unit)?) {
        val matchingChats = mutableListOf<TdApi.Chat>()
        var remaining = chatIds.size
        if (remaining == 0) {
            createPrivateStorageChannel(onReady)
            return
        }

        for (id in chatIds) {
            tdClient?.send(TdApi.GetChat(id)) { chatResult ->
                synchronized(matchingChats) {
                    if (chatResult is TdApi.Chat && chatResult.title.contains("VaultX", ignoreCase = true)) {
                        matchingChats.add(chatResult)
                    }
                    remaining--
                    if (remaining == 0) {
                        mainHandler.post {
                            isSettingUpChannel = false
                            if (matchingChats.isNotEmpty()) {
                                // Select existing channel
                                val selectedChat = matchingChats.first()
                                saveChannelPreference(selectedChat.id, selectedChat.title)
                                Log.d(tag, "Discovered existing VaultX storage channel: ${selectedChat.title} (ID: ${selectedChat.id})")

                                // Sync all files from every VaultX channel found so no files are ever lost!
                                for (chat in matchingChats) {
                                    syncChannelFiles(chat.id)
                                }
                                onReady?.invoke(selectedChat.id)
                            } else if (privateChannelId == 0L) {
                                createPrivateStorageChannel(onReady)
                            } else {
                                onReady?.invoke(privateChannelId)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun createPrivateStorageChannel(onReady: ((Long) -> Unit)? = null) {
        tdClient?.send(
            TdApi.CreateNewSupergroupChat(
                "VaultX Private Storage",
                true, // isChannel
                false, // isForum
                "Unlimited Cloud Storage Vault — Managed exclusively by VaultX on your device.",
                null,
                0,
                false
            )
        ) { result ->
            mainHandler.post {
                isSettingUpChannel = false
                if (result is TdApi.Chat) {
                    saveChannelPreference(result.id, result.title)
                    Log.d(tag, "Created brand new private VaultX channel ID: $privateChannelId")
                    onReady?.invoke(result.id)
                } else if (result is TdApi.Error) {
                    Log.e(tag, "Failed to create storage channel: ${result.message}")
                }
            }
        }
    }

    /**
     * Reads all historical uploaded files from the Telegram private channel
     * and restores them into local SQLite database (reinstallation recovery).
     */
    fun syncChannelFiles(channelId: Long, fromMessageId: Long = 0L, onFinished: (() -> Unit)? = null) {
        tdClient?.send(TdApi.GetChatHistory(channelId, fromMessageId, 0, 100, false)) { result ->
            if (result is TdApi.Messages) {
                val db = VaultXApplication.instance.databaseHelper
                var restoredInThisBatch = 0
                for (msg in result.messages) {
                    val item = parseMessageToFileItem(msg)
                    if (item != null) {
                        if (!db.hasFileWithTelegramId(item.telegramMessageId)) {
                            db.insertFile(item)
                            restoredInThisBatch++
                        }
                    }
                }

                if (restoredInThisBatch > 0) {
                    Log.d(tag, "Restored $restoredInThisBatch files from Telegram channel $channelId (batch)")
                    notifySyncListeners()
                }

                // If 100 messages returned, paginate backwards to retrieve all historical files!
                if (result.messages.size >= 100) {
                    val nextFromId = result.messages.last().id
                    syncChannelFiles(channelId, nextFromId, onFinished)
                } else {
                    notifySyncListeners()
                    mainHandler.post { onFinished?.invoke() }
                }
            } else {
                mainHandler.post { onFinished?.invoke() }
            }
        }
    }

    private fun parseMessageToFileItem(msg: TdApi.Message): FileItem? {
        when (val content = msg.content) {
            is TdApi.MessageDocument -> {
                val rawName = content.document.fileName
                val cap = content.caption.text
                val name = if (rawName.isNotBlank()) {
                    rawName
                } else if (cap.contains("VaultX_File:")) {
                    cap.substringAfter("VaultX_File:").substringBefore("|").trim()
                } else {
                    "Document_${msg.id}"
                }
                val size = content.document.document.size
                val mime = content.document.mimeType.ifBlank { "application/octet-stream" }
                val localPath = content.document.document.local?.path?.takeIf { it.isNotBlank() && File(it).exists() }
                return FileItem(
                    name = name,
                    telegramMessageId = msg.id,
                    sizeBytes = size,
                    mimeType = mime,
                    category = determineCategory(name, mime),
                    isFavorite = false,
                    isTrash = false,
                    uploadTimestamp = msg.date * 1000L,
                    localUri = localPath,
                    chunkCount = 1
                )
            }
            is TdApi.MessageVideo -> {
                val name = content.video.fileName.ifBlank { "Video_${msg.id}.mp4" }
                val size = content.video.video.size
                val mime = content.video.mimeType.ifBlank { "video/mp4" }
                val localPath = content.video.video.local?.path?.takeIf { it.isNotBlank() && File(it).exists() }
                return FileItem(
                    name = name,
                    telegramMessageId = msg.id,
                    sizeBytes = size,
                    mimeType = mime,
                    category = FileCategory.VIDEOS,
                    isFavorite = false,
                    isTrash = false,
                    uploadTimestamp = msg.date * 1000L,
                    localUri = localPath,
                    chunkCount = 1
                )
            }
            is TdApi.MessagePhoto -> {
                val largest = content.photo.sizes.lastOrNull()
                val name = "Photo_${msg.id}.jpg"
                val size = largest?.photo?.size?.toLong() ?: 0L
                val localPath = largest?.photo?.local?.path?.takeIf { it.isNotBlank() && File(it).exists() }
                return FileItem(
                    name = name,
                    telegramMessageId = msg.id,
                    sizeBytes = size,
                    mimeType = "image/jpeg",
                    category = FileCategory.PHOTOS,
                    isFavorite = false,
                    isTrash = false,
                    uploadTimestamp = msg.date * 1000L,
                    localUri = localPath,
                    chunkCount = 1
                )
            }
            is TdApi.MessageAudio -> {
                val name = content.audio.fileName.ifBlank { "Audio_${msg.id}.mp3" }
                val size = content.audio.audio.size
                val mime = content.audio.mimeType.ifBlank { "audio/mpeg" }
                val localPath = content.audio.audio.local?.path?.takeIf { it.isNotBlank() && File(it).exists() }
                return FileItem(
                    name = name,
                    telegramMessageId = msg.id,
                    sizeBytes = size,
                    mimeType = mime,
                    category = FileCategory.AUDIO,
                    isFavorite = false,
                    isTrash = false,
                    uploadTimestamp = msg.date * 1000L,
                    localUri = localPath,
                    chunkCount = 1
                )
            }
            else -> return null
        }
    }

    private val activeDownloads = mutableMapOf<Int, (File?) -> Unit>()

    private fun handleFileUpdate(file: TdApi.File) {
        if (file.local.isDownloadingCompleted && file.local.path.isNotBlank()) {
            val localFile = File(file.local.path)
            if (localFile.exists()) {
                val callback = synchronized(activeDownloads) {
                    activeDownloads.remove(file.id)
                }
                mainHandler.post {
                    callback?.invoke(localFile)
                }
            }
        }
    }

    /**
     * Downloads a file from Telegram servers on demand for viewing.
     */
    fun downloadFileFromTelegram(
        item: FileItem,
        onComplete: (File?) -> Unit
    ) {
        if (!item.localUri.isNullOrBlank()) {
            val local = File(item.localUri!!)
            if (local.exists()) {
                onComplete(local)
                return
            }
        }

        val targetChatId = privateChannelId
        if (targetChatId == 0L || item.telegramMessageId == 0L) {
            onComplete(null)
            return
        }

        tdClient?.send(TdApi.GetMessage(targetChatId, item.telegramMessageId)) { result ->
            if (result is TdApi.Message) {
                val tdlibFile = extractFileFromMessage(result)
                if (tdlibFile != null) {
                    if (tdlibFile.local.isDownloadingCompleted && tdlibFile.local.path.isNotBlank()) {
                        val downloaded = File(tdlibFile.local.path)
                        if (downloaded.exists()) {
                            VaultXApplication.instance.databaseHelper.updateLocalUri(item.id, downloaded.absolutePath)
                            mainHandler.post { onComplete(downloaded) }
                            return@send
                        }
                    }

                    synchronized(activeDownloads) {
                        activeDownloads[tdlibFile.id] = { completedFile ->
                            if (completedFile != null) {
                                VaultXApplication.instance.databaseHelper.updateLocalUri(item.id, completedFile.absolutePath)
                            }
                            onComplete(completedFile)
                        }
                    }

                    tdClient?.send(TdApi.DownloadFile(tdlibFile.id, 32, 0, 0, false)) { _ -> }
                } else {
                    mainHandler.post { onComplete(null) }
                }
            } else {
                mainHandler.post { onComplete(null) }
            }
        }
    }

    private fun extractFileFromMessage(msg: TdApi.Message): TdApi.File? {
        return when (val content = msg.content) {
            is TdApi.MessageDocument -> content.document.document
            is TdApi.MessageVideo -> content.video.video
            is TdApi.MessagePhoto -> content.photo.sizes.lastOrNull()?.photo
            is TdApi.MessageAudio -> content.audio.audio
            else -> null
        }
    }

    /**
     * Real upload of file directly to the user's private Telegram channel.
     */
    fun uploadFileToTelegram(
        filePath: String,
        fileName: String,
        sizeBytes: Long,
        mimeType: String,
        onProgress: ((Int) -> Unit)? = null,
        onSuccess: ((FileItem) -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        if (privateChannelId == 0L) {
            setupVaultStorageChannel { readyChannelId ->
                if (readyChannelId != 0L) {
                    uploadFileToTelegram(filePath, fileName, sizeBytes, mimeType, onProgress, onSuccess, onError)
                } else {
                    onError?.invoke("Storage channel not ready")
                }
            }
            return
        }

        val targetChatId = privateChannelId
        val inputFile = TdApi.InputFileLocal(filePath)
        val caption = ChunkedUploader.generateChunkCaption(fileName, 0, 1, sizeBytes)
        val formattedText = TdApi.FormattedText(caption, null)
        val inputMessageContent = TdApi.InputMessageDocument(inputFile, null, false, formattedText)

        tdClient?.send(
            TdApi.SendMessage(
                targetChatId,
                null,
                null,
                null,
                null,
                inputMessageContent
            )
        ) { result ->
            mainHandler.post {
                if (result is TdApi.Message) {
                    val fileItem = FileItem(
                        name = fileName,
                        telegramMessageId = result.id,
                        sizeBytes = sizeBytes,
                        mimeType = mimeType,
                        category = determineCategory(fileName, mimeType),
                        isFavorite = false,
                        uploadTimestamp = System.currentTimeMillis(),
                        localUri = filePath
                    )
                    onSuccess?.invoke(fileItem)
                } else if (result is TdApi.Error) {
                    onError?.invoke(result.message)
                }
            }
        }
    }

    fun logout() {
        tdClient?.send(TdApi.LogOut()) {
            mainHandler.post {
                _authState.value = TelegramAuthState.WAIT_PHONE
                privateChannelId = 0L
                phoneNumber = ""
                prefs.edit().clear().apply()
            }
        }
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
}

enum class TelegramAuthState {
    WAIT_PHONE,
    WAIT_CODE,
    WAIT_2FA,
    LOGGED_IN
}
