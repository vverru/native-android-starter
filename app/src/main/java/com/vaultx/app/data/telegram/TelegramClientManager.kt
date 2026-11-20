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
import java.io.FileOutputStream
import android.graphics.Bitmap
import android.graphics.BitmapFactory

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
            TdApi.UpdateMessageSendSucceeded.CONSTRUCTOR -> {
                val update = obj as TdApi.UpdateMessageSendSucceeded
                val oldId = update.oldMessageId
                val newMsg = update.message
                VaultXApplication.instance.databaseHelper.updateTelegramMessageId(oldId, newMsg.id)
                Log.d(tag, "Message ID mapped: oldId=$oldId -> newId=${newMsg.id}")
            }
            TdApi.UpdateNewChat.CONSTRUCTOR -> {
                val newChat = obj as TdApi.UpdateNewChat
                if (newChat.chat.title.contains("VaultX", ignoreCase = true)) {
                    if (privateChannelId != 0L && newChat.chat.id == privateChannelId) {
                        syncChannelFiles(privateChannelId)
                    } else if (privateChannelId == 0L) {
                        setupVaultStorageChannel()
                    }
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
                    if (privateChannelId != 0L && chat.id == privateChannelId) {
                        syncChannelFiles(privateChannelId)
                    } else if (privateChannelId == 0L) {
                        setupVaultStorageChannel()
                    }
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

        if (isSettingUpChannel) {
            onReady?.let { cb ->
                addSyncListener {
                    if (privateChannelId != 0L) cb.invoke(privateChannelId)
                }
            }
            return
        }
        isSettingUpChannel = true

        // Phase 1: Search local TDLib chat database for "VaultX"
        tdClient?.send(TdApi.SearchChats("VaultX", 100)) { searchResult ->
            if (searchResult is TdApi.Chats && searchResult.chatIds.isNotEmpty()) {
                inspectCandidateChats(searchResult.chatIds.toList(), onReady)
            } else {
                // Phase 2: Load chats from Telegram main chat list
                loadChatsAndInspect(0, onReady)
            }
        }
    }

    private fun loadChatsAndInspect(depth: Int, onReady: ((Long) -> Unit)?) {
        tdClient?.send(TdApi.LoadChats(TdApi.ChatListMain(), 100)) { loadResult ->
            tdClient?.send(TdApi.GetChats(TdApi.ChatListMain(), 300)) { getResult ->
                if (getResult is TdApi.Chats && getResult.chatIds.isNotEmpty()) {
                    val candidateIds = getResult.chatIds.toList()
                    inspectCandidateChats(candidateIds) { foundId ->
                        if (foundId != 0L) {
                            onReady?.invoke(foundId)
                        } else if (depth < 2 && loadResult is TdApi.Ok) {
                            loadChatsAndInspect(depth + 1, onReady)
                        } else {
                            createPrivateStorageChannel(onReady)
                        }
                    }
                } else {
                    if (depth < 2 && loadResult is TdApi.Ok) {
                        loadChatsAndInspect(depth + 1, onReady)
                    } else {
                        createPrivateStorageChannel(onReady)
                    }
                }
            }
        }
    }

    private fun inspectCandidateChats(chatIds: List<Long>, onReady: ((Long) -> Unit)?) {
        val matchingChats = mutableListOf<TdApi.Chat>()
        var remaining = chatIds.size
        if (remaining == 0) {
            onReady?.invoke(0L)
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
                            if (matchingChats.isNotEmpty()) {
                                isSettingUpChannel = false
                                // Deterministically sort candidates: pick the primary/oldest channel
                                matchingChats.sortBy { it.id }
                                val primaryChat = matchingChats.first()
                                saveChannelPreference(primaryChat.id, primaryChat.title)
                                Log.d(tag, "Locked onto primary VaultX channel: ${primaryChat.title} (ID: ${primaryChat.id})")

                                // Only sync from this locked primary channel
                                syncChannelFiles(primaryChat.id)
                                onReady?.invoke(primaryChat.id)
                            } else {
                                onReady?.invoke(0L)
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
        val vaultDir = File(context.filesDir, "vault_files")
        when (val content = msg.content) {
            is TdApi.MessageDocument -> {
                val cap = content.caption?.text ?: ""
                val rawName = content.document.fileName
                val isVaultX = cap.contains("VaultX_File:") || cap.contains("VaultX_Part")
                val localExisting = if (rawName.isNotBlank()) findVaultFile(vaultDir, rawName) else null

                // Strictly ignore files that were not uploaded through VaultX
                if (!isVaultX && localExisting == null) {
                    return null
                }

                val name = if (cap.contains("VaultX_File:")) {
                    cap.substringAfter("VaultX_File:").substringBefore("|").trim()
                } else if (cap.contains("VaultX_Part")) {
                    cap.substringAfter("]:").substringBefore("|").trim()
                } else if (localExisting != null) {
                    localExisting.name
                } else {
                    rawName
                }

                val size = content.document.document.size
                val mime = content.document.mimeType.ifBlank { "application/octet-stream" }
                val localPath = content.document.document.local?.path?.takeIf { it.isNotBlank() && File(it).exists() }
                    ?: localExisting?.absolutePath

                var thumbPath: String? = null
                val mini = content.document.minithumbnail
                if (mini != null && mini.data.isNotEmpty()) {
                    thumbPath = saveMiniThumbnail(msg.id, mini.data)
                }
                val thumb = content.document.thumbnail
                if (thumb != null) {
                    if (thumb.file.local.isDownloadingCompleted && thumb.file.local.path.isNotBlank() && File(thumb.file.local.path).exists()) {
                        thumbPath = thumb.file.local.path
                    } else {
                        synchronized(activeThumbnailDownloads) {
                            activeThumbnailDownloads[thumb.file.id] = msg.id
                        }
                        tdClient?.send(TdApi.DownloadFile(thumb.file.id, 1, 0, 0, false)) {}
                    }
                }

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
                    thumbnailUri = thumbPath ?: localPath,
                    chunkCount = 1
                )
            }
            is TdApi.MessageVideo -> {
                val cap = content.caption?.text ?: ""
                val rawName = content.video.fileName
                val isVaultX = cap.contains("VaultX_File:") || cap.contains("VaultX_Part")
                val localExisting = if (rawName.isNotBlank()) findVaultFile(vaultDir, rawName) else null

                // Strictly ignore files that were not uploaded through VaultX
                if (!isVaultX && localExisting == null) {
                    return null
                }

                val name = if (cap.contains("VaultX_File:")) {
                    cap.substringAfter("VaultX_File:").substringBefore("|").trim()
                } else if (cap.contains("VaultX_Part")) {
                    cap.substringAfter("]:").substringBefore("|").trim()
                } else if (localExisting != null) {
                    localExisting.name
                } else {
                    rawName
                }

                val size = content.video.video.size
                val mime = content.video.mimeType.ifBlank { "video/mp4" }
                val localPath = content.video.video.local?.path?.takeIf { it.isNotBlank() && File(it).exists() }
                    ?: localExisting?.absolutePath

                var thumbPath: String? = null
                val mini = content.video.minithumbnail
                if (mini != null && mini.data.isNotEmpty()) {
                    thumbPath = saveMiniThumbnail(msg.id, mini.data)
                }
                val thumb = content.video.thumbnail
                if (thumb != null) {
                    if (thumb.file.local.isDownloadingCompleted && thumb.file.local.path.isNotBlank() && File(thumb.file.local.path).exists()) {
                        thumbPath = thumb.file.local.path
                    } else {
                        synchronized(activeThumbnailDownloads) {
                            activeThumbnailDownloads[thumb.file.id] = msg.id
                        }
                        tdClient?.send(TdApi.DownloadFile(thumb.file.id, 1, 0, 0, false)) {}
                    }
                }

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
                    thumbnailUri = thumbPath ?: localPath,
                    chunkCount = 1
                )
            }
            is TdApi.MessagePhoto -> {
                val cap = content.caption?.text ?: ""
                val isVaultX = cap.contains("VaultX_File:") || cap.contains("VaultX_Part")
                if (!isVaultX) {
                    return null
                }
                val name = if (cap.contains("VaultX_File:")) {
                    cap.substringAfter("VaultX_File:").substringBefore("|").trim()
                } else {
                    cap.substringAfter("]:").substringBefore("|").trim()
                }
                val largest = content.photo.sizes.lastOrNull()
                val size = largest?.photo?.size?.toLong() ?: 0L
                val localExisting = findVaultFile(vaultDir, name)
                val localPath = largest?.photo?.local?.path?.takeIf { it.isNotBlank() && File(it).exists() }
                    ?: localExisting?.absolutePath

                var thumbPath: String? = null
                val mini = content.photo.minithumbnail
                if (mini != null && mini.data.isNotEmpty()) {
                    thumbPath = saveMiniThumbnail(msg.id, mini.data)
                }
                val smallest = content.photo.sizes.firstOrNull()
                if (smallest != null) {
                    if (smallest.photo.local.isDownloadingCompleted && smallest.photo.local.path.isNotBlank() && File(smallest.photo.local.path).exists()) {
                        thumbPath = smallest.photo.local.path
                    } else {
                        synchronized(activeThumbnailDownloads) {
                            activeThumbnailDownloads[smallest.photo.id] = msg.id
                        }
                        tdClient?.send(TdApi.DownloadFile(smallest.photo.id, 1, 0, 0, false)) {}
                    }
                }

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
                    thumbnailUri = thumbPath ?: localPath,
                    chunkCount = 1
                )
            }
            is TdApi.MessageAudio -> {
                val cap = content.caption?.text ?: ""
                val rawName = content.audio.fileName
                val isVaultX = cap.contains("VaultX_File:") || cap.contains("VaultX_Part")
                val localExisting = if (rawName.isNotBlank()) findVaultFile(vaultDir, rawName) else null

                if (!isVaultX && localExisting == null) {
                    return null
                }

                val name = if (cap.contains("VaultX_File:")) {
                    cap.substringAfter("VaultX_File:").substringBefore("|").trim()
                } else if (cap.contains("VaultX_Part")) {
                    cap.substringAfter("]:").substringBefore("|").trim()
                } else if (localExisting != null) {
                    localExisting.name
                } else {
                    rawName
                }

                val size = content.audio.audio.size
                val mime = content.audio.mimeType.ifBlank { "audio/mpeg" }
                val localExistingAudio = findVaultFile(vaultDir, name)
                val localPath = content.audio.audio.local?.path?.takeIf { it.isNotBlank() && File(it).exists() }
                    ?: localExistingAudio?.absolutePath
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
                    thumbnailUri = null,
                    chunkCount = 1
                )
            }
            else -> return null
        }
    }

    fun findVaultFile(dir: File, name: String): File? {
        if (!dir.exists()) return null
        val exact = File(dir, name)
        if (exact.exists()) return exact
        val files = dir.listFiles() ?: return null
        return files.firstOrNull { it.name.endsWith("_$name") || it.name == name }
    }

    private fun saveMiniThumbnail(msgId: Long, data: ByteArray): String? {
        return try {
            val thumbFile = File(context.cacheDir, "mini_${msgId}.jpg")
            if (thumbFile.exists() && thumbFile.length() > 0) return thumbFile.absolutePath
            FileOutputStream(thumbFile).use { it.write(data) }
            thumbFile.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    private val activeDownloads = mutableMapOf<Int, (File?) -> Unit>()
    private val activeThumbnailDownloads = mutableMapOf<Int, Long>()

    private fun handleFileUpdate(file: TdApi.File) {
        if (file.local.isDownloadingCompleted && file.local.path.isNotBlank()) {
            val localFile = File(file.local.path)
            if (localFile.exists()) {
                val callback = synchronized(activeDownloads) {
                    activeDownloads.remove(file.id)
                }
                if (callback != null) {
                    mainHandler.post {
                        callback.invoke(localFile)
                    }
                }

                val thumbMsgId = synchronized(activeThumbnailDownloads) {
                    activeThumbnailDownloads.remove(file.id)
                }
                if (thumbMsgId != null) {
                    VaultXApplication.instance.databaseHelper.updateThumbnailUriByTelegramMsgId(thumbMsgId, localFile.absolutePath)
                    notifySyncListeners()
                }
            }
        }
    }

    /**
     * On-demand background thumbnail request for messages that do not have thumbnails yet.
     */
    fun requestThumbnailForMessage(msgId: Long) {
        if (msgId <= 0L) return
        val targetChatId = privateChannelId
        if (targetChatId == 0L) return
        tdClient?.send(TdApi.GetMessage(targetChatId, msgId)) { result ->
            if (result is TdApi.Message) {
                val thumbFile = when (val c = result.content) {
                    is TdApi.MessageDocument -> c.document.thumbnail?.file
                    is TdApi.MessageVideo -> c.video.thumbnail?.file
                    is TdApi.MessagePhoto -> c.photo.sizes.firstOrNull()?.photo
                    else -> null
                }
                if (thumbFile != null) {
                    if (thumbFile.local.isDownloadingCompleted && thumbFile.local.path.isNotBlank() && File(thumbFile.local.path).exists()) {
                        VaultXApplication.instance.databaseHelper.updateThumbnailUriByTelegramMsgId(msgId, thumbFile.local.path)
                        notifySyncListeners()
                    } else {
                        synchronized(activeThumbnailDownloads) {
                            activeThumbnailDownloads[thumbFile.id] = msgId
                        }
                        tdClient?.send(TdApi.DownloadFile(thumbFile.id, 1, 0, 0, false)) {}
                    }
                }
            }
        }
    }

    private fun saveToVaultStorage(sourceFile: File, originalName: String): File {
        val vaultDir = File(context.filesDir, "vault_files").apply { mkdirs() }
        val dest = File(vaultDir, originalName)
        return try {
            if (sourceFile.canonicalPath != dest.canonicalPath) {
                sourceFile.copyTo(dest, overwrite = true)
            }
            dest
        } catch (_: Exception) {
            sourceFile
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
            val cleanPath = item.localUri!!.removePrefix("file://")
            val local = File(cleanPath)
            if (local.exists()) {
                mainHandler.post { onComplete(local) }
                return
            }
        }

        val localVault = findVaultFile(File(context.filesDir, "vault_files"), item.name)
        if (localVault != null && localVault.exists()) {
            VaultXApplication.instance.databaseHelper.updateLocalUri(item.id, localVault.absolutePath)
            mainHandler.post { onComplete(localVault) }
            return
        }

        if (privateChannelId == 0L) {
            setupVaultStorageChannel { readyChannelId ->
                if (readyChannelId != 0L) {
                    downloadFileFromTelegram(item, onComplete)
                } else {
                    mainHandler.post { onComplete(null) }
                }
            }
            return
        }

        val targetChatId = privateChannelId
        if (targetChatId == 0L || item.telegramMessageId == 0L) {
            mainHandler.post { onComplete(null) }
            return
        }

        tdClient?.send(TdApi.GetMessage(targetChatId, item.telegramMessageId)) { result ->
            if (result is TdApi.Message) {
                val tdlibFile = extractFileFromMessage(result)
                if (tdlibFile != null) {
                    if (tdlibFile.local.isDownloadingCompleted && tdlibFile.local.path.isNotBlank()) {
                        val downloaded = File(tdlibFile.local.path)
                        if (downloaded.exists()) {
                            val persistent = saveToVaultStorage(downloaded, item.name)
                            VaultXApplication.instance.databaseHelper.updateLocalUri(item.id, persistent.absolutePath)
                            mainHandler.post { onComplete(persistent) }
                            return@send
                        }
                    }

                    synchronized(activeDownloads) {
                        activeDownloads[tdlibFile.id] = { completedFile ->
                            if (completedFile != null && completedFile.exists()) {
                                val persistent = saveToVaultStorage(completedFile, item.name)
                                VaultXApplication.instance.databaseHelper.updateLocalUri(item.id, persistent.absolutePath)
                                mainHandler.post { onComplete(persistent) }
                            } else {
                                mainHandler.post { onComplete(null) }
                            }
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
        val category = determineCategory(fileName, mimeType)
        val inputThumbnail = when (category) {
            FileCategory.PHOTOS -> createInputThumbnail(filePath)
            FileCategory.VIDEOS -> createVideoThumbnail(filePath)
            else -> null
        }
        val inputMessageContent = TdApi.InputMessageDocument(inputFile, inputThumbnail, false, formattedText)

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
                        category = category,
                        isFavorite = false,
                        uploadTimestamp = System.currentTimeMillis(),
                        localUri = filePath,
                        thumbnailUri = filePath
                    )
                    onSuccess?.invoke(fileItem)
                } else if (result is TdApi.Error) {
                    onError?.invoke(result.message)
                }
            }
        }
    }

    private fun createInputThumbnail(imagePath: String): TdApi.InputThumbnail? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(imagePath, options)
            val origW = options.outWidth
            val origH = options.outHeight
            if (origW <= 0 || origH <= 0) return null

            val maxDim = 320
            var sample = 1
            while ((origW / sample) > maxDim || (origH / sample) > maxDim) {
                sample *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeFile(imagePath, decodeOpts) ?: return null
            val thumbFile = File(context.cacheDir, "up_thumb_${System.currentTimeMillis()}.jpg")
            FileOutputStream(thumbFile).use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            val targetW = bmp.width
            val targetH = bmp.height
            bmp.recycle()
            TdApi.InputThumbnail(TdApi.InputFileLocal(thumbFile.absolutePath), targetW, targetH)
        } catch (_: Exception) {
            null
        }
    }

    private fun createVideoThumbnail(videoPath: String): TdApi.InputThumbnail? {
        return try {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(videoPath)
            val bitmap = retriever.getFrameAtTime(1000000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
            retriever.release()
            if (bitmap != null) {
                val thumbFile = File(context.cacheDir, "up_vid_thumb_${System.currentTimeMillis()}.jpg")
                FileOutputStream(thumbFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
                }
                val targetW = bitmap.width
                val targetH = bitmap.height
                bitmap.recycle()
                TdApi.InputThumbnail(TdApi.InputFileLocal(thumbFile.absolutePath), targetW, targetH)
            } else null
        } catch (_: Exception) {
            null
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

    /**
     * Deletes a message permanently from the user's private Telegram storage channel.
     * With revoke = true, it deletes the file from Telegram servers.
     */
    fun deleteMessageFromTelegram(messageId: Long, onComplete: ((Boolean) -> Unit)? = null) {
        val targetChatId = privateChannelId
        if (targetChatId == 0L || messageId <= 0L) {
            onComplete?.invoke(false)
            return
        }
        tdClient?.send(TdApi.DeleteMessages(targetChatId, longArrayOf(messageId), true)) { result ->
            mainHandler.post {
                val success = result is TdApi.Ok
                if (success) {
                    Log.d(tag, "Successfully deleted message $messageId from Telegram channel $targetChatId")
                } else {
                    Log.e(tag, "Failed to delete message $messageId from Telegram: $result")
                }
                onComplete?.invoke(success)
            }
        }
    }

    /**
     * Complete unified deletion:
     * 1. Deletes the file from the user's private Telegram channel
     * 2. Deletes local file from vault_files and internal storage
     * 3. Deletes cached thumbnail
     * 4. Deletes entry from SQLite database
     */
    fun deleteFileFromCloudAndLocal(file: FileItem, onComplete: (() -> Unit)? = null) {
        // 1. Delete from Telegram Channel
        if (file.telegramMessageId > 0L) {
            deleteMessageFromTelegram(file.telegramMessageId)
        }

        // 2. Delete local files
        file.localUri?.let { path ->
            try {
                val f = File(path)
                if (f.exists()) f.delete()
            } catch (_: Exception) {}
        }
        file.thumbnailUri?.let { path ->
            try {
                val f = File(path)
                if (f.exists()) f.delete()
            } catch (_: Exception) {}
        }
        try {
            val vaultDir = File(context.filesDir, "vault_files")
            val localF = findVaultFile(vaultDir, file.name)
            if (localF != null && localF.exists()) localF.delete()
        } catch (_: Exception) {}

        // 3. Delete permanently from local SQLite
        VaultXApplication.instance.databaseHelper.deletePermanently(file.id)
        notifySyncListeners()
        mainHandler.post { onComplete?.invoke() }
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

    fun determineCategory(name: String, mimeType: String): FileCategory {
        val lower = name.lowercase()
        return when {
            mimeType.startsWith("image") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp") || lower.endsWith(".heic") || lower.endsWith(".heif") || lower.endsWith(".svg") -> FileCategory.PHOTOS
            mimeType.startsWith("video") || lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".mov") || lower.endsWith(".avi") || lower.endsWith(".3gp") || lower.endsWith(".webm") -> FileCategory.VIDEOS
            mimeType == "application/pdf" || lower.endsWith(".pdf") -> FileCategory.PDFS
            mimeType.startsWith("audio") || lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".wav") || lower.endsWith(".aac") || lower.endsWith(".ogg") || lower.endsWith(".flac") -> FileCategory.AUDIO
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
