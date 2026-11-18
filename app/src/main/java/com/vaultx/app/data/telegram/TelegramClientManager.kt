package com.vaultx.app.data.telegram

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
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

    var phoneNumber: String = ""
        private set

    var privateChannelId: Long = 0L
        private set

    var privateChannelTitle: String = "VaultX Private Storage"
        private set

    init {
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
                    privateChannelId = newChat.chat.id
                    privateChannelTitle = newChat.chat.title
                    Log.d(tag, "Found existing VaultX private storage channel ID: $privateChannelId")
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
                    privateChannelId = chat.id
                    privateChannelTitle = chat.title
                    Log.d(tag, "Resolved VaultX private storage channel: ${chat.title} ($privateChannelId)")
                }
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
     * Finds or creates a dedicated, private Telegram channel owned by the user.
     */
    fun setupVaultStorageChannel() {
        // Query user's existing chats to see if VaultX storage channel already exists
        tdClient?.send(TdApi.GetChats(null, 100)) { result ->
            if (result is TdApi.Chats) {
                var found = false
                for (id in result.chatIds) {
                    tdClient?.send(TdApi.GetChat(id)) { chatResult ->
                        if (chatResult is TdApi.Chat && chatResult.title.contains("VaultX", ignoreCase = true)) {
                            privateChannelId = chatResult.id
                            privateChannelTitle = chatResult.title
                            found = true
                        }
                    }
                }
                // If no VaultX channel exists, automatically create a brand new private supergroup channel!
                mainHandler.postDelayed({
                    if (!found && privateChannelId == 0L) {
                        createPrivateStorageChannel()
                    }
                }, 2000)
            } else {
                createPrivateStorageChannel()
            }
        }
    }

    private fun createPrivateStorageChannel() {
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
            if (result is TdApi.Chat) {
                privateChannelId = result.id
                privateChannelTitle = result.title
                Log.d(tag, "Created brand new private VaultX channel ID: $privateChannelId")
            }
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
        val targetChatId = if (privateChannelId != 0L) privateChannelId else -1001234567890L
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
