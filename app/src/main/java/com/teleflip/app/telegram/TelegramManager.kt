package com.teleflip.app.telegram

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.teleflip.app.model.ActiveCall
import com.teleflip.app.model.CallState
import com.teleflip.app.model.ChatFolderModel
import com.teleflip.app.model.ChatModel
import com.teleflip.app.model.ChatProfile
import com.teleflip.app.model.ChatType
import com.teleflip.app.model.ContactModel
import com.teleflip.app.model.ForumTopicModel
import com.teleflip.app.model.GroupMember
import com.teleflip.app.model.MediaType
import com.teleflip.app.model.MessageModel
import com.teleflip.app.model.MessageSearchResult
import com.teleflip.app.model.MessageStatus
import com.teleflip.app.model.PollOption
import com.teleflip.app.model.ProxyItem
import com.teleflip.app.model.ProxyType
import com.teleflip.app.model.SavedGifItem
import com.teleflip.app.model.SessionModel
import com.teleflip.app.model.StickerItem
import com.teleflip.app.model.StickerSetModel
import com.teleflip.app.model.UserProfileModel
import com.teleflip.app.notifications.NotificationHelper
import com.teleflip.app.storage.ChatExportManager
import com.teleflip.app.storage.ChatExportOptions
import com.teleflip.app.storage.ChatExportProgress
import com.teleflip.app.storage.ProxyStorage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume

sealed class AuthState {
    object Initial : AuthState()

    object WaitParameters : AuthState()

    data class WaitPhoneNumber(val errorMessage: String? = null) : AuthState()

    data class WaitOtherDeviceConfirmation(val link: String = "", val errorMessage: String? = null) : AuthState()

    data class WaitCode(val info: String = "", val errorMessage: String? = null) : AuthState()

    data class WaitPassword(
        val hint: String = "",
        val hasRecoveryEmailAddress: Boolean = false,
        val recoveryEmailAddressPattern: String = "",
        val errorMessage: String? = null,
    ) : AuthState()

    data class WaitPasswordRecoveryCode(
        val recoveryEmailPattern: String = "",
        val errorMessage: String? = null,
    ) : AuthState()

    object Ready : AuthState()

    data class Error(val message: String) : AuthState()
}

class TelegramManager(paramContext: Context) : Client.ResultHandler {
    private val context: Context = paramContext.applicationContext ?: paramContext
    private val tag = "TelegramManager"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs: SharedPreferences = context.getSharedPreferences("teleflip_prefs", Context.MODE_PRIVATE)

    private val _connectionState = MutableStateFlow("Offline")
    val connectionState: StateFlow<String> = _connectionState.asStateFlow()

    private val _authState = MutableStateFlow<AuthState>(AuthState.Initial)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    val chatMap = java.util.Collections.synchronizedMap(LinkedHashMap<Long, ChatModel>())

    private val _chats = MutableStateFlow<List<ChatModel>>(emptyList())
    val chats: StateFlow<List<ChatModel>> = _chats.asStateFlow()

    private val _messages = MutableStateFlow<Map<Long, List<MessageModel>>>(emptyMap())
    val messages: StateFlow<Map<Long, List<MessageModel>>> = _messages.asStateFlow()

    private val _activeCall = MutableStateFlow<ActiveCall?>(null)
    val activeCall: StateFlow<ActiveCall?> = _activeCall.asStateFlow()

    val subscriberCache = java.util.concurrent.ConcurrentHashMap<Long, Boolean>()
    val userSubscriberCache get() = subscriberCache
    val chatIdToUserId = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    val userPhoneCache = java.util.concurrent.ConcurrentHashMap<Long, String>()
    val userCache = java.util.concurrent.ConcurrentHashMap<Long, TdApi.User>()
    val chatDrafts = java.util.concurrent.ConcurrentHashMap<Long, String>()
    val forumTopicsCache = java.util.concurrent.ConcurrentHashMap<Long, List<ForumTopicModel>>()
    val groupMembersCache = java.util.concurrent.ConcurrentHashMap<Long, List<GroupMember>>()
    val chatNotificationSettingsCache = java.util.concurrent.ConcurrentHashMap<Long, TdApi.ChatNotificationSettings>()
    var scopePrivateChatsMuted = false
    var scopeGroupChatsMuted = false
    var scopeChannelChatsMuted = false
    private val prefMutedChatsKey = "teleflip_muted_chat_ids"
    val fileDownloadWaiters =
        java.util.concurrent.ConcurrentHashMap<Int, CopyOnWriteArrayList<CompletableDeferred<String?>>>()

    private val _chatFolders = MutableStateFlow<List<ChatFolderModel>>(emptyList())
    val chatFolders: StateFlow<List<ChatFolderModel>> = _chatFolders.asStateFlow()

    private val _currentUser = MutableStateFlow<UserProfileModel?>(null)
    val currentUser: StateFlow<UserProfileModel?> = _currentUser.asStateFlow()

    private var tdClient: Client? = null

    @androidx.annotation.VisibleForTesting
    internal fun setTdClientForTesting(client: Client?) {
        this.tdClient = client
    }

    @androidx.annotation.VisibleForTesting
    internal fun setChatsForTesting(chats: List<ChatModel>) {
        synchronized(chatMap) {
            chatMap.clear()
            chats.forEach { chatMap[it.id] = it }
        }
        _chats.value = chats
    }

    @androidx.annotation.VisibleForTesting
    internal fun setMessagesForTesting(messages: Map<Long, List<MessageModel>>) {
        _messages.value = messages
    }

    @androidx.annotation.VisibleForTesting
    internal fun handleIncomingMessageForTesting(msg: TdApi.Message) {
        handleIncomingMessage(msg)
    }

    @androidx.annotation.VisibleForTesting
    internal fun handleMessagesListForTesting(msgs: Array<TdApi.Message>) {
        handleMessagesList(msgs)
    }

    @androidx.annotation.VisibleForTesting
    internal fun setActiveCallForTesting(call: ActiveCall?) {
        _activeCall.value = call
    }

    @androidx.annotation.VisibleForTesting
    internal fun setAuthStateForTesting(state: AuthState) {
        _authState.value = state
    }

    @androidx.annotation.VisibleForTesting
    internal fun setConnectionStateForTesting(state: String) {
        _connectionState.value = state
    }

    @androidx.annotation.VisibleForTesting
    internal fun setChatFoldersForTesting(folders: List<ChatFolderModel>) {
        _chatFolders.value = folders
    }

    var chatBatchSize: Int = DEFAULT_CHAT_BATCH_SIZE

    companion object {
        const val DEFAULT_CHAT_BATCH_SIZE = 10

        @Volatile
        private var instance: TelegramManager? = null

        fun getInstance(context: Context): TelegramManager {
            return instance ?: synchronized(this) {
                instance ?: TelegramManager(context.applicationContext ?: context).also { instance = it }
            }
        }

        @androidx.annotation.VisibleForTesting
        internal fun resetInstance() {
            synchronized(this) {
                instance = null
            }
        }

        @androidx.annotation.VisibleForTesting
        internal fun resetInstanceForTesting() {
            synchronized(this) {
                instance = null
            }
        }

        @androidx.annotation.VisibleForTesting
        internal fun setInstanceForTesting(manager: TelegramManager?) {
            synchronized(this) {
                instance = manager
            }
        }

        private val customEmojiCache = java.util.concurrent.ConcurrentHashMap<String, StickerItem>()

        fun registerCustomEmojiSticker(
            key: String,
            item: StickerItem,
        ) {
            customEmojiCache[key] = item
        }

        fun getCustomEmojiSticker(key: String): StickerItem? = customEmojiCache[key]

        fun getCustomEmojiFilePath(key: String): String? {
            val item = customEmojiCache[key] ?: return null
            val path = item.filePath ?: return null
            return if (File(path).exists()) path else null
        }

        fun clearCustomEmojiCache() {
            customEmojiCache.clear()
        }
    }

    private suspend fun sendQuery(query: TdApi.Function<*>): TdApi.Object =
        suspendCancellableCoroutine { continuation ->
            val client = tdClient
            if (client == null) {
                continuation.resumeWith(Result.failure(IllegalStateException("TDLib client is null")))
                return@suspendCancellableCoroutine
            }
            try {
                client.send(query) { obj ->
                    if (continuation.isActive) {
                        if (obj is TdApi.Error) {
                            continuation.resumeWith(Result.failure(Exception("TDLib Error [${obj.code}]: ${obj.message}")))
                        } else if (obj != null) {
                            continuation.resumeWith(Result.success(obj))
                        } else {
                            continuation.resumeWith(Result.failure(NullPointerException("Received null result from TDLib")))
                        }
                    }
                }
            } catch (e: Exception) {
                if (continuation.isActive) {
                    continuation.resumeWith(Result.failure(e))
                }
            }
        }

    fun dispatchChatBatches(
        chatIds: LongArray,
        batchSize: Int = chatBatchSize,
    ) {
        if (chatIds.isEmpty()) return
        scope.launch {
            val chunks = chatIds.toList().chunked(batchSize)
            for (chunk in chunks) {
                coroutineScope {
                    for (id in chunk) {
                        launch {
                            try {
                                val result =
                                    withTimeoutOrNull(10000L) {
                                        sendQuery(TdApi.GetChat(id))
                                    }
                                if (result is TdApi.Chat) {
                                    addOrUpdateChat(result)
                                } else if (result == null) {
                                    com.teleflip.app.report.AdbReporter.reportError(
                                        tag,
                                        "Timeout fetching chat $id in batch chunk",
                                    )
                                }
                            } catch (e: Exception) {
                                com.teleflip.app.report.AdbReporter.reportError(
                                    tag,
                                    "Failed to fetch chat $id in batch chunk: ${e.message}",
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Telegram API credentials
    var apiId: Int
        get() {
            val v = prefs.getInt("api_id", 0)
            if (v != 0 && v != 21724 && v != 4) return v
            return org.telegram.messenger.BuildVars.APP_ID
        }
        set(value) = prefs.edit().putInt("api_id", value).apply()

    var apiHash: String
        get() {
            val h = prefs.getString("api_hash", null)
            if (!h.isNullOrBlank() && h != "3e0cb5da6ec029b3a0b168a6b23d57f7" && h != "014b35b6184100b085b0d0572f9b5103") return h
            return org.telegram.messenger.BuildVars.APP_HASH
        }
        set(value) = prefs.edit().putString("api_hash", value).apply()

    var keypadTimeoutMs: Long
        get() {
            return try {
                val v = prefs.getLong("keypad_timeout_ms", 500L)
                if (v == 300L || v == 500L || v == 900L) v else 500L
            } catch (e: Exception) {
                try {
                    val v = prefs.getInt("keypad_timeout_ms", 500).toLong()
                    if (v == 300L || v == 500L || v == 900L) v else 500L
                } catch (e2: Exception) {
                    500L
                }
            }
        }
        set(value) {
            val validValue = if (value == 300L || value == 500L || value == 900L) value else 500L
            prefs.edit().putLong("keypad_timeout_ms", validValue).apply()
        }

    var backgroundPushEnabled: Boolean
        get() =
            try {
                prefs.getBoolean("bg_push_enabled", true)
            } catch (_: Exception) {
                true
            }
        set(value) {
            prefs.edit()?.putBoolean("bg_push_enabled", value)?.apply()
        }

    init {
        if (instance == null) {
            instance = this
        }
        try {
            System.loadLibrary("tdjni")
            Client.execute(TdApi.SetLogVerbosityLevel(1))
            com.teleflip.app.report.AdbReporter.reportInfo(tag, "TDLib tdjni native library loaded successfully.")
        } catch (e: Throwable) {
            com.teleflip.app.report.AdbReporter.reportCrash(tag, e)
        }
    }

    private val clientLock = Any()

    @Volatile
    private var isStartingClient = false

    @Volatile
    private var isClosingClient = false

    @Volatile
    private var reconnectPending = false

    fun start() {
        synchronized(clientLock) {
            if (tdClient != null || isStartingClient) {
                com.teleflip.app.report.AdbReporter.reportInfo(tag, "TDLib Client already active or starting; skipping start()")
                return
            }
            if (isClosingClient) {
                com.teleflip.app.report.AdbReporter.reportInfo(tag, "TDLib Client is closing; queuing reconnect on close")
                reconnectPending = true
                return
            }
            isStartingClient = true
        }
        scope.launch(Dispatchers.IO) {
            try {
                _connectionState.value = "Connecting..."
                com.teleflip.app.report.AdbReporter.reportInfo(tag, "Creating TDLib Client...")
                val client = Client.create(this@TelegramManager, null, null)
                synchronized(clientLock) {
                    isStartingClient = false
                    if (client == null) {
                        _connectionState.value = "Init error"
                        com.teleflip.app.report.AdbReporter.reportError(tag, "Client.create returned null")
                    } else {
                        tdClient = client
                        applySavedProxy()
                    }
                }
            } catch (e: Throwable) {
                synchronized(clientLock) {
                    isStartingClient = false
                }
                com.teleflip.app.report.AdbReporter.reportError(tag, "Client creation error: ${e.message}", e)
                _connectionState.value = "Init error"
            }
        }
    }

    fun stop() {
        clearAuthTimeoutSafeguard()
        isAuthQueryInProgress = false
        scope.launch(Dispatchers.IO) {
            try {
                com.teleflip.app.report.AdbReporter.reportInfo(tag, "Closing TDLib client instance...")
                tdClient?.send(TdApi.Close(), this@TelegramManager)
            } catch (e: Throwable) {
                Log.w(tag, "Error during native client teardown: ${e.message}")
            }
            tdClient = null
            _connectionState.value = "Offline"
        }
    }

    fun retryConnection() {
        clearAuthTimeoutSafeguard()
        isAuthQueryInProgress = false
        val activeClient: Client?
        synchronized(clientLock) {
            activeClient = tdClient
            if (activeClient == null && !isStartingClient) {
                reconnectPending = false
                start()
                return
            }
            isClosingClient = true
            reconnectPending = true
        }
        scope.launch(Dispatchers.IO) {
            try {
                com.teleflip.app.report.AdbReporter.reportInfo(tag, "Closing TDLib client for clean reconnection...")
                activeClient?.send(TdApi.Close(), this@TelegramManager)
            } catch (e: Throwable) {
                Log.w(tag, "Error during native client teardown: ${e.message}")
                synchronized(clientLock) {
                    isClosingClient = false
                    tdClient = null
                }
                start()
            }
        }
    }

    private val _isAuthQueryInProgress = MutableStateFlow(false)
    val isAuthQueryInProgressFlow: StateFlow<Boolean> = _isAuthQueryInProgress.asStateFlow()

    var isAuthQueryInProgress: Boolean
        get() = _isAuthQueryInProgress.value
        private set(value) {
            _isAuthQueryInProgress.value = value
        }

    private var authTimeoutJob: Job? = null

    private fun startAuthTimeoutSafeguard() {
        authTimeoutJob?.cancel()
        authTimeoutJob =
            scope.launch {
                delay(15_000L)
                if (isAuthQueryInProgress) {
                    com.teleflip.app.report.AdbReporter.reportError(tag, "Auth query timed out after 15 seconds")
                    isAuthQueryInProgress = false
                    val timeoutMsg = "Request timed out after 15 seconds. Please try again."
                    when (val curr = _authState.value) {
                        is AuthState.WaitPhoneNumber -> _authState.value = curr.copy(errorMessage = timeoutMsg)
                        is AuthState.WaitOtherDeviceConfirmation -> _authState.value = curr.copy(errorMessage = timeoutMsg)
                        is AuthState.WaitCode -> _authState.value = curr.copy(errorMessage = timeoutMsg)
                        is AuthState.WaitPassword -> _authState.value = curr.copy(errorMessage = timeoutMsg)
                        else -> _authState.value = AuthState.Error(timeoutMsg)
                    }
                }
            }
    }

    private fun clearAuthTimeoutSafeguard() {
        authTimeoutJob?.cancel()
        authTimeoutJob = null
    }

    private fun isClientActive(): Boolean {
        return tdClient != null && _connectionState.value != "Init error"
    }

    private fun handleClientUnavailable() {
        com.teleflip.app.report.AdbReporter.reportError(tag, "Authorization submission attempted while TDLib client is unavailable")
        val msg = "Telegram client unavailable. Please check connection or tap Retry Connection."
        when (val curr = _authState.value) {
            is AuthState.WaitPhoneNumber -> _authState.value = curr.copy(errorMessage = msg)
            is AuthState.WaitOtherDeviceConfirmation -> _authState.value = curr.copy(errorMessage = msg)
            is AuthState.WaitCode -> _authState.value = curr.copy(errorMessage = msg)
            is AuthState.WaitPassword -> _authState.value = curr.copy(errorMessage = msg)
            else -> _authState.value = AuthState.WaitPhoneNumber(errorMessage = msg)
        }
    }

    fun sendPhoneNumber(phone: String) {
        if (isAuthQueryInProgress) {
            com.teleflip.app.report.AdbReporter.reportInfo(tag, "sendPhoneNumber ignored: authorization query already in flight")
            return
        }
        if (!isClientActive()) {
            handleClientUnavailable()
            return
        }
        isAuthQueryInProgress = true
        startAuthTimeoutSafeguard()
        val cleanPhone = org.telegram.PhoneFormat.PhoneFormat.stripExceptNumbers(phone, true) ?: phone
        val normalized = if (!cleanPhone.startsWith("+")) "+$cleanPhone" else cleanPhone
        com.teleflip.app.report.AdbReporter.reportInfo(tag, "Submitting phone number: $normalized")
        val settings =
            TdApi.PhoneNumberAuthenticationSettings().apply {
                allowFlashCall = false
                allowMissedCall = false
                isCurrentPhoneNumber = true
                allowSmsRetrieverApi = false
            }
        tdClient?.send(TdApi.SetAuthenticationPhoneNumber(normalized, settings), this)
    }

    fun requestQrCodeLogin() {
        if (isAuthQueryInProgress) {
            com.teleflip.app.report.AdbReporter.reportInfo(tag, "requestQrCodeLogin ignored: authorization query already in flight")
            return
        }
        if (!isClientActive()) {
            handleClientUnavailable()
            return
        }
        isAuthQueryInProgress = true
        startAuthTimeoutSafeguard()
        com.teleflip.app.report.AdbReporter.reportInfo(tag, "Requesting QR code authentication")
        tdClient?.send(TdApi.RequestQrCodeAuthentication(longArrayOf()), this)
    }

    fun showPhoneNumberLogin() {
        _authState.value = AuthState.WaitPhoneNumber()
    }

    fun checkCode(code: String) {
        if (isAuthQueryInProgress) {
            com.teleflip.app.report.AdbReporter.reportInfo(tag, "checkCode ignored: authorization query already in flight")
            return
        }
        if (!isClientActive()) {
            handleClientUnavailable()
            return
        }
        isAuthQueryInProgress = true
        startAuthTimeoutSafeguard()
        tdClient?.send(TdApi.CheckAuthenticationCode(code), this)
    }

    fun checkPassword(password: String) {
        if (isAuthQueryInProgress) {
            com.teleflip.app.report.AdbReporter.reportInfo(tag, "checkPassword ignored: authorization query already in flight")
            return
        }
        if (!isClientActive()) {
            handleClientUnavailable()
            return
        }
        isAuthQueryInProgress = true
        startAuthTimeoutSafeguard()
        tdClient?.send(TdApi.CheckAuthenticationPassword(password), this)
    }

    fun requestPasswordRecovery() {
        if (isAuthQueryInProgress) {
            com.teleflip.app.report.AdbReporter.reportInfo(tag, "requestPasswordRecovery ignored: authorization query already in flight")
            return
        }
        if (!isClientActive()) {
            handleClientUnavailable()
            return
        }
        isAuthQueryInProgress = true
        tdClient?.send(TdApi.RequestAuthenticationPasswordRecovery(), this)
    }

    fun recoverPassword(code: String) {
        if (isAuthQueryInProgress) {
            com.teleflip.app.report.AdbReporter.reportInfo(tag, "recoverPassword ignored: authorization query already in flight")
            return
        }
        if (!isClientActive()) {
            handleClientUnavailable()
            return
        }
        isAuthQueryInProgress = true
        tdClient?.send(TdApi.RecoverAuthenticationPassword(code, "", ""), this)
    }

    fun resetAccount() {
        if (isAuthQueryInProgress) {
            com.teleflip.app.report.AdbReporter.reportInfo(tag, "resetAccount ignored: authorization query already in flight")
            return
        }
        if (!isClientActive()) {
            handleClientUnavailable()
            return
        }
        isAuthQueryInProgress = true
        tdClient?.send(TdApi.DeleteAccount("Account reset requested from login view", ""), this)
    }

    internal fun createSendOptions(
        disableNotification: Boolean = false,
        schedulingState: TdApi.MessageSchedulingState? = null,
    ): TdApi.MessageSendOptions {
        val effectiveSchedulingState =
            if (schedulingState is TdApi.MessageSchedulingStateSendAtDate) {
                val nowSec = (System.currentTimeMillis() / 1000).toInt()
                if (schedulingState.sendDate <= nowSec) null else schedulingState
            } else {
                schedulingState
            }
        return TdApi.MessageSendOptions().apply {
            this.disableNotification = disableNotification
            this.schedulingState = effectiveSchedulingState
        }
    }

    fun sendMessage(
        chatId: Long,
        text: String,
        replyToMessageId: Long = 0L,
        replyToPreview: String = "",
        disableNotification: Boolean = false,
        schedulingState: TdApi.MessageSchedulingState? = null,
    ) {
        if (text.isBlank()) return

        val resolvedPreview =
            if (replyToPreview.isNotBlank()) {
                replyToPreview
            } else if (replyToMessageId != 0L) {
                val target = _messages.value[chatId]?.find { it.id == replyToMessageId }
                if (target != null) "${target.senderName}: ${target.text.take(40)}" else ""
            } else {
                ""
            }

        saveChatDraft(chatId, "")

        val activeChatModel = chatMap[chatId]
        val threadId = activeChatModel?.activeTopicId ?: 0L
        val ttlSec = activeChatModel?.selfDestructTimerSec ?: 0
        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val newMsg =
            MessageModel(
                id = System.currentTimeMillis(),
                chatId = chatId,
                senderName = "You",
                text = text,
                time = timeStr,
                isOutbound = true,
                status = MessageStatus.SENT,
                replyToMessageId = replyToMessageId,
                replyToPreview = resolvedPreview,
                messageThreadId = threadId,
                selfDestructSeconds = ttlSec,
            )

        appendMessage(chatId, newMsg)

        // Live TDLib send text message
        val content =
            TdApi.InputMessageText(
                TdApi.FormattedText(text, emptyArray()),
                null,
                true,
            )
        val sendReq =
            TdApi.SendMessage().apply {
                this.chatId = chatId
                this.inputMessageContent = content
                this.options = createSendOptions(disableNotification, schedulingState)
                if (replyToMessageId != 0L) {
                    applyReplyToMessageId(this, replyToMessageId)
                }
            }
        tdClient?.send(sendReq, this)
    }

    private fun applyReplyToMessageId(
        sendReq: TdApi.SendMessage,
        replyToMessageId: Long,
    ) {
        try {
            val field = sendReq.javaClass.getField("replyToMessageId")
            field.setLong(sendReq, replyToMessageId)
        } catch (e: Throwable) {
            try {
                val replyToField = sendReq.javaClass.getField("replyTo")
                val replyClass = Class.forName("org.drinkless.tdlib.TdApi\$InputMessageReplyToMessage")
                val replyObj = replyClass.getDeclaredConstructor().newInstance()
                replyClass.getField("messageId").setLong(replyObj, replyToMessageId)
                replyToField.set(sendReq, replyObj)
            } catch (ex: Throwable) {
                Log.w(tag, "Reply field mapping not supported in this TDLib build: ${ex.message}")
            }
        }
    }

    fun editMessageText(
        chatId: Long,
        messageId: Long,
        newText: String,
        onComplete: ((Boolean) -> Unit)? = null,
    ) {
        val trimmed = newText.trim()
        if (trimmed.isEmpty()) {
            onComplete?.invoke(false)
            return
        }

        val currentMap = _messages.value.toMutableMap()
        val chatMsgs = (currentMap[chatId] ?: emptyList()).toMutableList()
        val idx = chatMsgs.indexOfFirst { it.id == messageId }
        if (idx >= 0) {
            val updatedMsg = chatMsgs[idx].copy(text = trimmed, isEdited = true)
            chatMsgs[idx] = updatedMsg
            currentMap[chatId] = chatMsgs
            _messages.value = currentMap

            if (idx == chatMsgs.lastIndex) {
                synchronized(chatMap) {
                    val existing = chatMap[chatId]
                    if (existing != null) {
                        chatMap[chatId] = existing.copy(lastMessage = trimmed)
                    }
                }
                _chats.value = synchronized(chatMap) { chatMap.values.toList() }
            }
        }

        val client = tdClient
        if (client == null) {
            onComplete?.invoke(idx >= 0)
            return
        }

        try {
            val content =
                TdApi.InputMessageText(
                    TdApi.FormattedText(trimmed, emptyArray()),
                    null,
                    true,
                )
            val req =
                TdApi.EditMessageText().apply {
                    this.chatId = chatId
                    this.messageId = messageId
                    this.inputMessageContent = content
                }
            client.send(req) { result ->
                val ok = result is TdApi.Message || result is TdApi.Ok
                scope.launch(Dispatchers.Main) {
                    onComplete?.invoke(ok)
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to edit message via TDLib: ${e.message}")
            onComplete?.invoke(idx >= 0)
        }
    }

    fun toggleMessageReaction(
        chatId: Long,
        messageId: Long,
        emoji: String,
    ) {
        if (emoji.isBlank()) return
        val currentMap = _messages.value.toMutableMap()
        val chatMsgs = (currentMap[chatId] ?: emptyList()).toMutableList()
        val idx = chatMsgs.indexOfFirst { it.id == messageId }
        var added = true
        if (idx >= 0) {
            val msg = chatMsgs[idx]
            val updatedReactions = msg.reactions.toMutableList()
            if (updatedReactions.contains(emoji)) {
                updatedReactions.remove(emoji)
                added = false
            } else {
                updatedReactions.add(emoji)
                added = true
            }
            chatMsgs[idx] = msg.copy(reactions = updatedReactions)
            currentMap[chatId] = chatMsgs
            _messages.value = currentMap
        }

        val client = tdClient ?: return
        try {
            val reactionType =
                if (emoji.startsWith("custom_emoji_")) {
                    val customEmojiId = emoji.removePrefix("custom_emoji_").toLongOrNull() ?: 0L
                    if (customEmojiId != 0L) {
                        try {
                            val reactionTypeClass = Class.forName("org.drinkless.tdlib.TdApi\$ReactionTypeCustomEmoji")
                            val inst = reactionTypeClass.getDeclaredConstructor().newInstance()
                            reactionTypeClass.getField("customEmojiId").setLong(inst, customEmojiId)
                            inst
                        } catch (_: Throwable) {
                            val reactionTypeClass = Class.forName("org.drinkless.tdlib.TdApi\$ReactionTypeEmoji")
                            val inst = reactionTypeClass.getDeclaredConstructor().newInstance()
                            reactionTypeClass.getField("emoji").set(inst, emoji)
                            inst
                        }
                    } else {
                        val reactionTypeClass = Class.forName("org.drinkless.tdlib.TdApi\$ReactionTypeEmoji")
                        val inst = reactionTypeClass.getDeclaredConstructor().newInstance()
                        reactionTypeClass.getField("emoji").set(inst, emoji)
                        inst
                    }
                } else {
                    val reactionTypeClass = Class.forName("org.drinkless.tdlib.TdApi\$ReactionTypeEmoji")
                    val inst = reactionTypeClass.getDeclaredConstructor().newInstance()
                    reactionTypeClass.getField("emoji").set(inst, emoji)
                    inst
                }

            val funcClassName =
                if (added) {
                    "org.drinkless.tdlib.TdApi\$AddMessageReaction"
                } else {
                    "org.drinkless.tdlib.TdApi\$RemoveMessageReaction"
                }
            val funcClass = Class.forName(funcClassName)
            val funcObj = funcClass.getDeclaredConstructor().newInstance() as TdApi.Function<*>
            funcClass.getField("chatId").setLong(funcObj, chatId)
            funcClass.getField("messageId").setLong(funcObj, messageId)
            funcClass.getField("reactionType").set(funcObj, reactionType)
            client.send(funcObj, this)
        } catch (e: Throwable) {
            Log.w(tag, "Reaction API dispatch skipped: ${e.message}")
        }
    }

    fun toggleChatPin(
        chatId: Long,
        currentPinned: Boolean,
        onResult: ((Boolean) -> Unit)? = null,
    ) {
        val newPinned = !currentPinned
        synchronized(chatMap) {
            val existing = chatMap[chatId]
            if (existing != null) {
                chatMap[chatId] = existing.copy(isPinned = newPinned)
            }
            val sortedList = chatMap.values.sortedByDescending { it.isPinned }
            chatMap.clear()
            sortedList.forEach { chatMap[it.id] = it }
        }
        _chats.value = synchronized(chatMap) { chatMap.values.toList() }

        val client = tdClient
        if (client == null) {
            onResult?.invoke(newPinned)
            return
        }

        try {
            val req =
                TdApi.ToggleChatIsPinned().apply {
                    this.chatList = TdApi.ChatListMain()
                    this.chatId = chatId
                    this.isPinned = newPinned
                }
            client.send(req) { obj ->
                val ok = obj is TdApi.Ok
                scope.launch(Dispatchers.Main) {
                    onResult?.invoke(if (ok) newPinned else currentPinned)
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "toggleChatPin failed: ${e.message}")
            onResult?.invoke(newPinned)
        }
    }

    fun togglePinMessage(
        chatId: Long,
        messageId: Long,
        currentPinned: Boolean,
        onResult: ((Boolean) -> Unit)? = null,
    ) {
        val newPinned = !currentPinned
        val currentMap = _messages.value.toMutableMap()
        val chatMsgs =
            (currentMap[chatId] ?: emptyList()).map { msg ->
                when {
                    msg.id == messageId -> msg.copy(isPinned = newPinned)
                    newPinned -> msg.copy(isPinned = false)
                    else -> msg
                }
            }
        currentMap[chatId] = chatMsgs
        _messages.value = currentMap

        synchronized(chatMap) {
            val existing = chatMap[chatId]
            if (existing != null) {
                chatMap[chatId] = existing.copy(pinnedMessageId = if (newPinned) messageId else 0L)
            }
        }
        _chats.value = synchronized(chatMap) { chatMap.values.toList() }

        val client = tdClient
        if (client == null) {
            onResult?.invoke(newPinned)
            return
        }

        try {
            val req: TdApi.Function<*> =
                if (newPinned) {
                    TdApi.PinChatMessage().apply {
                        this.chatId = chatId
                        this.messageId = messageId
                        this.disableNotification = false
                        this.onlyForSelf = false
                    }
                } else {
                    TdApi.UnpinChatMessage().apply {
                        this.chatId = chatId
                        this.messageId = messageId
                    }
                }
            client.send(req) { obj ->
                val ok = obj is TdApi.Ok
                scope.launch(Dispatchers.Main) {
                    onResult?.invoke(if (ok) newPinned else currentPinned)
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "togglePinMessage failed: ${e.message}")
            onResult?.invoke(newPinned)
        }
    }

    fun markChatAsRead(chatId: Long) {
        val chatMsgs = _messages.value[chatId] ?: emptyList()
        val unreadIds = chatMsgs.filter { !it.isOutbound }.map { it.id }.toLongArray()
        if (unreadIds.isNotEmpty()) {
            markMessagesAsRead(chatId, unreadIds)
        }
        synchronized(chatMap) {
            val existing = chatMap[chatId]
            if (existing != null) {
                chatMap[chatId] = existing.copy(unreadCount = 0)
            }
        }
        _chats.value = synchronized(chatMap) { chatMap.values.toList() }
    }

    fun saveChatDraft(
        chatId: Long,
        draftText: String,
    ) {
        val trimmed = draftText.trim()
        if (trimmed.isEmpty()) {
            chatDrafts.remove(chatId)
        } else {
            chatDrafts[chatId] = trimmed
        }
        synchronized(chatMap) {
            val existing = chatMap[chatId]
            if (existing != null && existing.draftText != trimmed) {
                chatMap[chatId] = existing.copy(draftText = trimmed)
            }
        }
        _chats.value = synchronized(chatMap) { chatMap.values.toList() }

        val draftMessage =
            if (trimmed.isNotEmpty()) {
                TdApi.DraftMessage(
                    null,
                    (System.currentTimeMillis() / 1000).toInt(),
                    TdApi.DraftMessageContentText(
                        TdApi.FormattedText(trimmed, emptyArray()),
                        null,
                    ),
                    0L,
                    null,
                )
            } else {
                null
            }
        val req = TdApi.SetChatDraftMessage(chatId, null, draftMessage)
        tdClient?.send(req, this)
    }

    fun handleChatDraftUpdated(
        chatId: Long,
        draftText: String,
    ) {
        val trimmed = draftText.trim()
        if (trimmed.isEmpty()) {
            chatDrafts.remove(chatId)
        } else {
            chatDrafts[chatId] = trimmed
        }
        synchronized(chatMap) {
            val existing = chatMap[chatId]
            if (existing != null && existing.draftText != trimmed) {
                chatMap[chatId] = existing.copy(draftText = trimmed)
            }
        }
        _chats.value = synchronized(chatMap) { chatMap.values.toList() }
    }

    fun getChatDraft(chatId: Long): String = chatDrafts[chatId] ?: ""

    fun sendPhoto(
        chatId: Long,
        filePath: String,
        caption: String = "",
        disableNotification: Boolean = false,
        schedulingState: TdApi.MessageSchedulingState? = null,
    ) {
        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val displayText = if (caption.isNotBlank()) "📷 [Photo] $caption" else "📷 [Photo]"
        val newMsg =
            MessageModel(
                id = System.currentTimeMillis(),
                chatId = chatId,
                senderName = "You",
                text = displayText,
                time = timeStr,
                isOutbound = true,
                status = MessageStatus.SENT,
                mediaType = MediaType.PHOTO,
                mediaCaption = caption,
                mediaFilePath = filePath,
            )
        appendMessage(chatId, newMsg)

        try {
            val inputPhoto =
                TdApi.InputPhoto().apply {
                    this.photo = TdApi.InputFileLocal(filePath)
                }
            val photoContent =
                TdApi.InputMessagePhoto().apply {
                    this.photo = inputPhoto
                    this.caption = TdApi.FormattedText(caption, emptyArray())
                }
            val sendReq =
                TdApi.SendMessage().apply {
                    this.chatId = chatId
                    this.inputMessageContent = photoContent
                    this.options = createSendOptions(disableNotification, schedulingState)
                }
            tdClient?.send(sendReq, this)
        } catch (e: Exception) {
            Log.e(tag, "Failed to send photo via TDLib: ${e.message}")
        }
    }

    fun sendDocument(
        chatId: Long,
        filePath: String,
        caption: String = "",
        disableNotification: Boolean = false,
        schedulingState: TdApi.MessageSchedulingState? = null,
    ) {
        val fileName = File(filePath).name.ifBlank { "Document" }
        val effectiveCaption = caption.ifBlank { fileName }
        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val newMsg =
            MessageModel(
                id = System.currentTimeMillis(),
                chatId = chatId,
                senderName = "You",
                text = "📄 $effectiveCaption",
                time = timeStr,
                isOutbound = true,
                status = MessageStatus.SENT,
                mediaType = MediaType.DOCUMENT,
                mediaCaption = effectiveCaption,
                mediaFilePath = filePath,
            )
        appendMessage(chatId, newMsg)

        try {
            val inputDoc =
                TdApi.InputDocument().apply {
                    this.document = TdApi.InputFileLocal(filePath)
                }
            val docContent =
                TdApi.InputMessageDocument().apply {
                    this.document = inputDoc
                    this.caption = TdApi.FormattedText(caption, emptyArray())
                }
            val sendReq =
                TdApi.SendMessage().apply {
                    this.chatId = chatId
                    this.inputMessageContent = docContent
                    this.options = createSendOptions(disableNotification, schedulingState)
                }
            tdClient?.send(sendReq, this)
        } catch (e: Exception) {
            Log.e(tag, "Failed to send document via TDLib: ${e.message}")
        }
    }

    fun sendStickerEmoji(
        chatId: Long,
        stickerEmoji: String,
        disableNotification: Boolean = false,
        schedulingState: TdApi.MessageSchedulingState? = null,
    ) {
        if (stickerEmoji.isBlank()) return
        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val newMsg =
            MessageModel(
                id = System.currentTimeMillis(),
                chatId = chatId,
                senderName = "You",
                text = "🎨 [Sticker $stickerEmoji]",
                time = timeStr,
                isOutbound = true,
                status = MessageStatus.SENT,
                mediaType = MediaType.STICKER,
                stickerEmoji = stickerEmoji,
            )
        appendMessage(chatId, newMsg)

        val content =
            TdApi.InputMessageText(
                TdApi.FormattedText(stickerEmoji, emptyArray()),
                null,
                true,
            )
        val sendReq =
            TdApi.SendMessage().apply {
                this.chatId = chatId
                this.inputMessageContent = content
                this.options = createSendOptions(disableNotification, schedulingState)
            }
        tdClient?.send(sendReq, this)
    }

    fun sendSticker(
        chatId: Long,
        stickerFileId: Int,
        emoji: String = "",
        disableNotification: Boolean = false,
        schedulingState: TdApi.MessageSchedulingState? = null,
    ) {
        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val newMsg =
            MessageModel(
                id = System.currentTimeMillis(),
                chatId = chatId,
                senderName = "You",
                text = if (emoji.isNotBlank()) "🎨 [Sticker $emoji]" else "🎨 [Sticker]",
                time = timeStr,
                isOutbound = true,
                status = MessageStatus.SENT,
                mediaType = MediaType.STICKER,
                stickerEmoji = emoji,
                mediaFileId = stickerFileId,
            )
        appendMessage(chatId, newMsg)

        try {
            val inputSticker =
                TdApi.InputSticker().apply {
                    this.sticker = TdApi.InputFileId(stickerFileId)
                }
            val content =
                TdApi.InputMessageSticker().apply {
                    this.sticker = inputSticker
                    this.emoji = emoji
                }
            val req =
                TdApi.SendMessage().apply {
                    this.chatId = chatId
                    this.inputMessageContent = content
                    this.options = createSendOptions(disableNotification, schedulingState)
                }
            tdClient?.send(req, this)
        } catch (e: Exception) {
            Log.e(tag, "Failed to send sticker via TDLib: ${e.message}")
        }
    }

    fun loadInstalledStickers(onResult: (List<StickerSetModel>) -> Unit) {
        val client = tdClient
        if (client == null) {
            onResult(emptyList())
            return
        }
        client.send(TdApi.GetInstalledStickerSets(TdApi.StickerTypeRegular())) { obj ->
            if (obj is TdApi.StickerSets) {
                val resultSets = mutableListOf<StickerSetModel>()
                val count = obj.sets.size
                if (count == 0) {
                    scope.launch(Dispatchers.Main) { onResult(emptyList()) }
                    return@send
                }
                var completed = 0
                for (info in obj.sets) {
                    client.send(TdApi.GetStickerSet(info.id)) { setObj ->
                        if (setObj is TdApi.StickerSet) {
                            val items =
                                setObj.stickers.map { st ->
                                    StickerItem(
                                        fileId = st.sticker.id,
                                        emoji = st.emoji,
                                        filePath = if (st.sticker.local.isDownloadingCompleted) st.sticker.local.path else null,
                                        setName = setObj.title,
                                    )
                                }
                            synchronized(resultSets) {
                                resultSets.add(StickerSetModel(setObj.id, setObj.title, items))
                            }
                        }
                        completed++
                        if (completed >= count) {
                            scope.launch(Dispatchers.Main) {
                                onResult(resultSets)
                            }
                        }
                    }
                }
            } else {
                scope.launch(Dispatchers.Main) { onResult(emptyList()) }
            }
        }
    }

    fun getCustomEmojiFilePath(key: String): String? = Companion.getCustomEmojiFilePath(key)

    fun getCustomEmojiSticker(key: String): StickerItem? = Companion.getCustomEmojiSticker(key)

    fun registerCustomEmojiSticker(
        key: String,
        item: StickerItem,
    ) {
        Companion.registerCustomEmojiSticker(key, item)
    }

    fun fetchCustomEmojiStickers(onResult: (List<StickerItem>) -> Unit) {
        val client = tdClient
        val resultList = mutableListOf<StickerItem>()

        val defaults =
            listOf(
                StickerItem(fileId = 1001, emoji = "⭐", filePath = null, setName = "Custom Emoji 1001"),
                StickerItem(fileId = 1002, emoji = "🚀", filePath = null, setName = "Custom Emoji 1002"),
                StickerItem(fileId = 1003, emoji = "💎", filePath = null, setName = "Custom Emoji 1003"),
                StickerItem(fileId = 1004, emoji = "⚡", filePath = null, setName = "Custom Emoji 1004"),
                StickerItem(fileId = 1005, emoji = "🎉", filePath = null, setName = "Custom Emoji 1005"),
            )
        defaults.forEach { item ->
            val key = "custom_emoji_${item.fileId}"
            registerCustomEmojiSticker(key, item)
        }

        if (client == null) {
            onResult(defaults)
            return
        }

        try {
            client.send(TdApi.GetRecentStickers(false)) { obj ->
                if (obj is TdApi.Stickers) {
                    obj.stickers.forEach { st ->
                        val customEmojiId =
                            if (st.fullType is TdApi.StickerFullTypeCustomEmoji) {
                                (st.fullType as TdApi.StickerFullTypeCustomEmoji).customEmojiId
                            } else {
                                st.id
                            }
                        val item =
                            StickerItem(
                                fileId = st.sticker.id,
                                emoji = if (st.emoji.isNotBlank()) st.emoji else "✨",
                                filePath = if (st.sticker.local.isDownloadingCompleted) st.sticker.local.path else null,
                                setName = "Custom Emoji $customEmojiId",
                            )
                        val key = "custom_emoji_$customEmojiId"
                        registerCustomEmojiSticker(key, item)
                        registerCustomEmojiSticker("custom_emoji_${st.sticker.id}", item)
                        synchronized(resultList) {
                            resultList.add(item)
                        }
                        if (!st.sticker.local.isDownloadingCompleted) {
                            resolveFile(st.sticker)
                        }
                    }
                }
                client.send(TdApi.GetInstalledStickerSets(TdApi.StickerTypeCustomEmoji())) { setObj ->
                    if (setObj is TdApi.StickerSets) {
                        var pending = setObj.sets.size
                        if (pending == 0) {
                            val finalItems = (resultList + defaults).distinctBy { it.fileId }
                            scope.launch(Dispatchers.Main) { onResult(finalItems) }
                        } else {
                            for (info in setObj.sets) {
                                client.send(TdApi.GetStickerSet(info.id)) { set ->
                                    if (set is TdApi.StickerSet) {
                                        set.stickers.forEach { st ->
                                            val customEmojiId =
                                                if (st.fullType is TdApi.StickerFullTypeCustomEmoji) {
                                                    (st.fullType as TdApi.StickerFullTypeCustomEmoji).customEmojiId
                                                } else {
                                                    st.id
                                                }
                                            val item =
                                                StickerItem(
                                                    fileId = st.sticker.id,
                                                    emoji = if (st.emoji.isNotBlank()) st.emoji else "✨",
                                                    filePath = if (st.sticker.local.isDownloadingCompleted) st.sticker.local.path else null,
                                                    setName = set.title,
                                                )
                                            val key = "custom_emoji_$customEmojiId"
                                            registerCustomEmojiSticker(key, item)
                                            registerCustomEmojiSticker("custom_emoji_${st.sticker.id}", item)
                                            synchronized(resultList) {
                                                resultList.add(item)
                                            }
                                            if (!st.sticker.local.isDownloadingCompleted) {
                                                resolveFile(st.sticker)
                                            }
                                        }
                                    }
                                    pending--
                                    if (pending <= 0) {
                                        val finalItems = (resultList + defaults).distinctBy { it.fileId }
                                        scope.launch(Dispatchers.Main) { onResult(finalItems) }
                                    }
                                }
                            }
                        }
                    } else {
                        val finalItems = (resultList + defaults).distinctBy { it.fileId }
                        scope.launch(Dispatchers.Main) { onResult(finalItems) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to fetch custom emoji stickers: ${e.message}")
            onResult(defaults)
        }
    }

    fun loadSavedGifs(onResult: (List<SavedGifItem>) -> Unit) {
        val client = tdClient
        if (client == null) {
            onResult(emptyList())
            return
        }
        client.send(TdApi.GetSavedAnimations()) { obj ->
            val list = mutableListOf<SavedGifItem>()
            if (obj is TdApi.Animations) {
                for (anim in obj.animations) {
                    val file = anim.animation
                    val path =
                        if (file.local.isDownloadingCompleted && file.local.path.isNotBlank()) {
                            file.local.path
                        } else {
                            if (file.local.canBeDownloaded && !file.local.isDownloadingActive) {
                                client.send(TdApi.DownloadFile(file.id, 1, 0, 0, false), this)
                            }
                            null
                        }
                    list.add(
                        SavedGifItem(
                            fileId = file.id,
                            filePath = path,
                            durationSeconds = anim.duration,
                            caption = anim.fileName.ifBlank { "GIF" },
                        ),
                    )
                }
            }
            scope.launch(Dispatchers.Main) {
                onResult(list)
            }
        }
    }

    fun sendSavedAnimation(
        chatId: Long,
        animFileId: Int,
        duration: Int = 0,
        caption: String = "",
        disableNotification: Boolean = false,
        schedulingState: TdApi.MessageSchedulingState? = null,
    ) {
        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val display = if (caption.isNotBlank()) "🎞️ [GIF] $caption" else "🎞️ [GIF]"
        val newMsg =
            MessageModel(
                id = System.currentTimeMillis(),
                chatId = chatId,
                senderName = "You",
                text = display,
                time = timeStr,
                isOutbound = true,
                status = MessageStatus.SENT,
                mediaType = MediaType.ANIMATION,
                mediaCaption = caption,
                mediaFileId = animFileId,
                durationSeconds = duration,
            )
        appendMessage(chatId, newMsg)

        try {
            val inputAnim =
                TdApi.InputAnimation().apply {
                    this.animation = TdApi.InputFileId(animFileId)
                    this.duration = duration
                }
            val content =
                TdApi.InputMessageAnimation().apply {
                    this.animation = inputAnim
                    this.caption = TdApi.FormattedText(caption, emptyArray())
                }
            val req =
                TdApi.SendMessage().apply {
                    this.chatId = chatId
                    this.inputMessageContent = content
                    this.options = createSendOptions(disableNotification, schedulingState)
                }
            tdClient?.send(req, this)
        } catch (e: Exception) {
            Log.e(tag, "Failed to send saved animation via TDLib: ${e.message}")
        }
    }

    fun sendVideo(
        chatId: Long,
        filePath: String,
        durationSeconds: Int = 0,
        caption: String = "",
        disableNotification: Boolean = false,
        schedulingState: TdApi.MessageSchedulingState? = null,
    ) {
        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val display = if (caption.isNotBlank()) "📹 [Video] $caption" else "📹 [Video]"
        val newMsg =
            MessageModel(
                id = System.currentTimeMillis(),
                chatId = chatId,
                senderName = "You",
                text = display,
                time = timeStr,
                isOutbound = true,
                status = MessageStatus.SENT,
                mediaType = MediaType.VIDEO,
                mediaCaption = caption,
                mediaFilePath = filePath,
                durationSeconds = durationSeconds,
            )
        appendMessage(chatId, newMsg)

        try {
            val inputVideo =
                TdApi.InputVideo().apply {
                    this.video = TdApi.InputFileLocal(filePath)
                    this.duration = durationSeconds
                }
            val videoContent =
                TdApi.InputMessageVideo().apply {
                    this.video = inputVideo
                    this.caption = TdApi.FormattedText(caption, emptyArray())
                }
            val sendReq =
                TdApi.SendMessage().apply {
                    this.chatId = chatId
                    this.inputMessageContent = videoContent
                    this.options = createSendOptions(disableNotification, schedulingState)
                }
            tdClient?.send(sendReq, this)
        } catch (e: Exception) {
            Log.e(tag, "Failed to send video via TDLib: ${e.message}")
        }
    }

    fun sendAnimation(
        chatId: Long,
        filePath: String,
        caption: String = "",
        disableNotification: Boolean = false,
        schedulingState: TdApi.MessageSchedulingState? = null,
    ) {
        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val display = if (caption.isNotBlank()) "🎞️ [GIF] $caption" else "🎞️ [GIF]"
        val newMsg =
            MessageModel(
                id = System.currentTimeMillis(),
                chatId = chatId,
                senderName = "You",
                text = display,
                time = timeStr,
                isOutbound = true,
                status = MessageStatus.SENT,
                mediaType = MediaType.ANIMATION,
                mediaCaption = caption,
                mediaFilePath = filePath,
            )
        appendMessage(chatId, newMsg)

        try {
            val inputAnim =
                TdApi.InputAnimation().apply {
                    this.animation = TdApi.InputFileLocal(filePath)
                }
            val animContent =
                TdApi.InputMessageAnimation().apply {
                    this.animation = inputAnim
                    this.caption = TdApi.FormattedText(caption, emptyArray())
                }
            val sendReq =
                TdApi.SendMessage().apply {
                    this.chatId = chatId
                    this.inputMessageContent = animContent
                    this.options = createSendOptions(disableNotification, schedulingState)
                }
            tdClient?.send(sendReq, this)
        } catch (e: Exception) {
            Log.e(tag, "Failed to send GIF via TDLib: ${e.message}")
        }
    }

    fun sendPoll(
        chatId: Long,
        question: String,
        options: List<String>,
        isAnonymous: Boolean = true,
        disableNotification: Boolean = false,
        schedulingState: TdApi.MessageSchedulingState? = null,
    ) {
        val trimmedQuestion = question.trim()
        val cleanOptions = options.map { it.trim() }.filter { it.isNotEmpty() }
        if (trimmedQuestion.isEmpty() || cleanOptions.size < 2) return

        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val pollOpts = cleanOptions.map { PollOption(text = it, voterCount = 0, votePercentage = 0, isChosen = false) }
        val newMsg =
            MessageModel(
                id = System.currentTimeMillis(),
                chatId = chatId,
                senderName = "You",
                text = "📊 Poll: $trimmedQuestion",
                time = timeStr,
                isOutbound = true,
                status = MessageStatus.SENT,
                mediaType = MediaType.POLL,
                pollQuestion = trimmedQuestion,
                pollOptions = pollOpts,
                pollIsAnonymous = isAnonymous,
                pollIsClosed = false,
                pollTotalVoters = 0,
            )
        appendMessage(chatId, newMsg)

        try {
            val pollContent =
                TdApi.InputMessagePoll().apply {
                    this.isAnonymous = isAnonymous
                    this.type = TdApi.InputPollTypeRegular(false)
                }
            setPollQuestionAndOptionsCompat(pollContent, trimmedQuestion, cleanOptions)
            val sendReq =
                TdApi.SendMessage().apply {
                    this.chatId = chatId
                    this.inputMessageContent = pollContent
                    this.options = createSendOptions(disableNotification, schedulingState)
                }
            tdClient?.send(sendReq, this)
        } catch (e: Throwable) {
            Log.w(tag, "Failed to dispatch TDLib InputMessagePoll: ${e.message}")
        }
    }

    private fun setPollQuestionAndOptionsCompat(
        pollContent: TdApi.InputMessagePoll,
        question: String,
        options: List<String>,
    ) {
        try {
            val qField = pollContent.javaClass.getField("question")
            if (qField.type == String::class.java) {
                qField.set(pollContent, question)
            } else {
                qField.set(pollContent, TdApi.FormattedText(question, emptyArray()))
            }
            val optField = pollContent.javaClass.getField("options")
            if (optField.type == Array<String>::class.java) {
                optField.set(pollContent, options.toTypedArray())
            } else if (optField.type == Array<TdApi.InputPollOption>::class.java) {
                val inputPollOptions =
                    options.map { TdApi.InputPollOption(TdApi.FormattedText(it, emptyArray()), null) }.toTypedArray()
                optField.set(pollContent, inputPollOptions)
            } else {
                val ftArray = options.map { TdApi.FormattedText(it, emptyArray()) }.toTypedArray()
                optField.set(pollContent, ftArray)
            }
        } catch (_: Throwable) {
        }
    }

    fun votePoll(
        chatId: Long,
        messageId: Long,
        optionIndex: Int,
    ) {
        val currentMap = _messages.value.toMutableMap()
        val list = (currentMap[chatId] ?: return).toMutableList()
        val idx = list.indexOfFirst { it.id == messageId }
        if (idx >= 0) {
            val msg = list[idx]
            if (!msg.pollIsClosed && optionIndex in msg.pollOptions.indices) {
                val rawCounts =
                    msg.pollOptions.mapIndexed { i, opt ->
                        val base = if (opt.isChosen) (opt.voterCount - 1).coerceAtLeast(0) else opt.voterCount
                        if (i == optionIndex) base + 1 else base
                    }
                val total = rawCounts.sum().coerceAtLeast(1)
                val updatedOpts =
                    msg.pollOptions.mapIndexed { i, opt ->
                        val count = rawCounts[i]
                        PollOption(
                            text = opt.text,
                            voterCount = count,
                            votePercentage = (count * 100) / total,
                            isChosen = (i == optionIndex),
                        )
                    }
                list[idx] = msg.copy(pollOptions = updatedOpts, pollTotalVoters = rawCounts.sum())
                currentMap[chatId] = list
                _messages.value = currentMap
            }
        }

        try {
            val req =
                TdApi.SetPollAnswer().apply {
                    this.chatId = chatId
                    this.messageId = messageId
                    this.optionIds = intArrayOf(optionIndex)
                }
            tdClient?.send(req, this)
        } catch (e: Exception) {
            Log.w(tag, "votePoll TDLib call failed: ${e.message}")
        }
    }

    fun stopPoll(
        chatId: Long,
        messageId: Long,
    ) {
        val currentMap = _messages.value.toMutableMap()
        val list = (currentMap[chatId] ?: return).toMutableList()
        val idx = list.indexOfFirst { it.id == messageId }
        if (idx >= 0) {
            list[idx] = list[idx].copy(pollIsClosed = true)
            currentMap[chatId] = list
            _messages.value = currentMap
        }

        try {
            val req =
                TdApi.StopPoll().apply {
                    this.chatId = chatId
                    this.messageId = messageId
                }
            tdClient?.send(req, this)
        } catch (e: Exception) {
            Log.w(tag, "stopPoll TDLib call failed: ${e.message}")
        }
    }

    fun toggleChatArchived(
        chatId: Long,
        currentArchived: Boolean,
        onResult: ((Boolean) -> Unit)? = null,
    ) {
        val newArchived = !currentArchived
        synchronized(chatMap) {
            val existing = chatMap[chatId]
            if (existing != null) {
                chatMap[chatId] = existing.copy(isArchived = newArchived, isPinned = if (newArchived) false else existing.isPinned)
            }
        }
        _chats.value = synchronized(chatMap) { chatMap.values.toList() }

        val client = tdClient
        if (client == null) {
            onResult?.invoke(newArchived)
            return
        }

        try {
            val req =
                TdApi.AddChatToList().apply {
                    this.chatId = chatId
                    this.chatList = if (newArchived) TdApi.ChatListArchive() else TdApi.ChatListMain()
                }
            client.send(req) { obj ->
                val ok = obj is TdApi.Ok
                scope.launch(Dispatchers.Main) {
                    onResult?.invoke(if (ok) newArchived else currentArchived)
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "toggleChatArchived failed: ${e.message}")
            onResult?.invoke(newArchived)
        }
    }

    fun assignChatToFolder(
        chatId: Long,
        folderName: String,
    ) {
        val cleanFolder = folderName.trim()
        synchronized(chatMap) {
            val existing = chatMap[chatId]
            if (existing != null) {
                chatMap[chatId] = existing.copy(folderName = cleanFolder)
            }
        }
        _chats.value = synchronized(chatMap) { chatMap.values.toList() }

        val currentFolders = _chatFolders.value.toMutableList()
        if (cleanFolder.isNotEmpty()) {
            val existingIdx = currentFolders.indexOfFirst { it.title.equals(cleanFolder, ignoreCase = true) }
            if (existingIdx >= 0) {
                val folder = currentFolders[existingIdx]
                currentFolders[existingIdx] = folder.copy(includedChatIds = folder.includedChatIds + chatId)
            } else {
                currentFolders.add(
                    ChatFolderModel(
                        id = currentFolders.size + 1,
                        title = cleanFolder,
                        includedChatIds = setOf(chatId),
                    ),
                )
            }
        } else {
            for (i in currentFolders.indices) {
                currentFolders[i] = currentFolders[i].copy(includedChatIds = currentFolders[i].includedChatIds - chatId)
            }
        }
        _chatFolders.value = currentFolders.filter { it.includedChatIds.isNotEmpty() }
    }

    fun startSecretChat(
        targetQueryOrName: String,
        userId: Long = 0L,
        onResult: (ChatModel?, String?) -> Unit,
    ) {
        val cleanTitle = targetQueryOrName.trim().ifEmpty { "Secret Contact" }
        val resolvedUserId =
            if (userId != 0L) {
                userId
            } else {
                kotlin.math.abs(cleanTitle.hashCode().toLong()).coerceAtLeast(5000L)
            }
        val secretId = (resolvedUserId % 100000).toInt().coerceAtLeast(1)
        val letters =
            cleanTitle.removePrefix("@").split(" ")
                .filter { it.isNotBlank() }
                .take(2)
                .map { it.first().uppercaseChar() }
                .joinToString("")
                .ifEmpty { "SC" }

        val client = tdClient
        if (client == null) {
            val secretChat =
                ChatModel(
                    id = -resolvedUserId,
                    title = cleanTitle,
                    lastMessage = "🔒 Secret Chat • E2E Encrypted",
                    time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
                    unreadCount = 0,
                    chatType = ChatType.DIRECT,
                    avatarLetters = letters,
                    isSecretChat = true,
                    secretChatId = secretId,
                    selfDestructTimerSec = 30,
                )
            synchronized(chatMap) {
                chatMap[secretChat.id] = secretChat
                val list = listOf(secretChat) + chatMap.values.filter { it.id != secretChat.id }
                chatMap.clear()
                list.forEach { chatMap[it.id] = it }
            }
            _chats.value = synchronized(chatMap) { chatMap.values.toList() }
            onResult(secretChat, null)
            return
        }

        scope.launch {
            try {
                val req = TdApi.CreateNewSecretChat(resolvedUserId)
                val chatObj = withTimeoutOrNull(5000L) { sendQuery(req) } as? TdApi.Chat
                if (chatObj != null) {
                    val model =
                        ChatModel(
                            id = chatObj.id,
                            title = chatObj.title.ifBlank { cleanTitle },
                            lastMessage = "🔒 Secret Chat • E2E Encrypted",
                            time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
                            unreadCount = 0,
                            chatType = ChatType.DIRECT,
                            avatarLetters = letters,
                            isSecretChat = true,
                            secretChatId = secretId,
                        )
                    synchronized(chatMap) {
                        chatMap[model.id] = model
                        val list = listOf(model) + chatMap.values.filter { it.id != model.id }
                        chatMap.clear()
                        list.forEach { chatMap[it.id] = it }
                    }
                    _chats.value = synchronized(chatMap) { chatMap.values.toList() }
                    withContext(Dispatchers.Main) { onResult(model, null) }
                } else {
                    withContext(Dispatchers.Main) { onResult(null, "Unable to initiate Secret Chat") }
                }
            } catch (e: Exception) {
                try {
                    withContext(Dispatchers.Main) { onResult(null, e.message) }
                } catch (_: Throwable) {
                    onResult(null, e.message)
                }
            }
        }
    }

    fun setSelfDestructTimer(
        chatId: Long,
        seconds: Int,
        onResult: ((Int) -> Unit)? = null,
    ) {
        val validSec = seconds.coerceAtLeast(0)
        synchronized(chatMap) {
            val existing = chatMap[chatId]
            if (existing != null) {
                chatMap[chatId] = existing.copy(selfDestructTimerSec = validSec)
            }
        }
        _chats.value = synchronized(chatMap) { chatMap.values.toList() }

        try {
            val ttlClass = Class.forName("org.drinkless.tdlib.TdApi\$SetChatMessageAutoDeleteTime")
            val ttlReq = ttlClass.getDeclaredConstructor().newInstance() as TdApi.Function<*>
            ttlClass.getField("chatId").setLong(ttlReq, chatId)
            ttlClass.getField("messageAutoDeleteTime").setInt(ttlReq, validSec)
            tdClient?.send(ttlReq, this)
        } catch (_: Throwable) {
        }
        onResult?.invoke(validSec)
    }

    fun loadForumTopics(
        chatId: Long,
        onResult: (List<ForumTopicModel>) -> Unit,
    ) {
        val cached = forumTopicsCache[chatId]
        if (cached != null && cached.isNotEmpty()) {
            onResult(cached.sortedByDescending { it.isPinned })
            return
        }
        val client = tdClient
        if (client != null) {
            try {
                val req =
                    TdApi.GetForumTopics().apply {
                        this.chatId = chatId
                        this.query = ""
                        this.offsetDate = 0
                        this.offsetMessageId = 0
                        this.offsetForumTopicId = 0
                        this.limit = 100
                    }
                client.send(req) { obj ->
                    val topicsList =
                        if (obj is TdApi.ForumTopics) {
                            obj.topics.map { topic ->
                                ForumTopicModel(
                                    topicId = topic.info.forumTopicId.toLong(),
                                    name = topic.info.name,
                                    unreadCount = topic.unreadCount,
                                    iconEmoji = "💬",
                                    isClosed = topic.info.isClosed,
                                    isPinned = topic.isPinned,
                                )
                            }.sortedByDescending { it.isPinned }
                        } else {
                            null
                        }

                    scope.launch(Dispatchers.Main) {
                        if (!topicsList.isNullOrEmpty()) {
                            forumTopicsCache[chatId] = topicsList
                            onResult(topicsList)
                        } else {
                            val defaultTopics =
                                listOf(
                                    ForumTopicModel(topicId = 0L, name = "General (All Messages)", unreadCount = 0, iconEmoji = "💬"),
                                    ForumTopicModel(topicId = 101L, name = "Announcements", unreadCount = 0, iconEmoji = "📢"),
                                    ForumTopicModel(topicId = 102L, name = "Support & Q&A", unreadCount = 0, iconEmoji = "🛠️"),
                                ).sortedByDescending { it.isPinned }
                            forumTopicsCache[chatId] = defaultTopics
                            onResult(defaultTopics)
                        }
                    }
                }
                return
            } catch (e: Exception) {
                Log.w(tag, "loadForumTopics query failed: ${e.message}")
            }
        }
        val defaultTopics =
            listOf(
                ForumTopicModel(topicId = 0L, name = "General (All Messages)", unreadCount = 0, iconEmoji = "💬"),
                ForumTopicModel(topicId = 101L, name = "Announcements", unreadCount = 0, iconEmoji = "📢"),
                ForumTopicModel(topicId = 102L, name = "Support & Q&A", unreadCount = 0, iconEmoji = "🛠️"),
            ).sortedByDescending { it.isPinned }
        forumTopicsCache[chatId] = defaultTopics
        onResult(defaultTopics)
    }

    fun createForumTopic(
        chatId: Long,
        name: String,
        iconEmoji: String = "💬",
        onResult: (ForumTopicModel) -> Unit,
    ) {
        val cleanName = name.trim().ifEmpty { "New Topic" }
        val newTopic =
            ForumTopicModel(
                topicId = System.currentTimeMillis() % 100000L,
                name = cleanName,
                unreadCount = 0,
                iconEmoji = iconEmoji,
            )
        val existing = forumTopicsCache[chatId] ?: listOf(ForumTopicModel(0L, "General (All Messages)", 0, "💬"))
        val updated = (existing + newTopic).sortedByDescending { it.isPinned }
        forumTopicsCache[chatId] = updated
        selectForumTopic(chatId, newTopic.topicId, newTopic.name)
        onResult(newTopic)
    }

    fun toggleForumTopicPin(
        chatId: Long,
        topicId: Long,
        currentPinned: Boolean,
        onResult: ((Boolean, String?) -> Unit)? = null,
    ) {
        val newPinned = !currentPinned
        val existing =
            forumTopicsCache[chatId] ?: listOf(
                ForumTopicModel(topicId = 0L, name = "General (All Messages)", unreadCount = 0, iconEmoji = "💬"),
                ForumTopicModel(topicId = 101L, name = "Announcements", unreadCount = 0, iconEmoji = "📢"),
                ForumTopicModel(topicId = 102L, name = "Support & Q&A", unreadCount = 0, iconEmoji = "🛠️"),
            )
        val updated =
            existing.map {
                if (it.topicId == topicId) it.copy(isPinned = newPinned) else it
            }.sortedByDescending { it.isPinned }
        forumTopicsCache[chatId] = updated

        val client = tdClient
        if (client == null) {
            onResult?.invoke(newPinned, null)
            return
        }

        try {
            val req =
                TdApi.ToggleForumTopicIsPinned().apply {
                    this.chatId = chatId
                    this.forumTopicId = topicId.toInt()
                    this.isPinned = newPinned
                }
            client.send(req) { obj ->
                if (obj is TdApi.Ok) {
                    scope.launch(Dispatchers.Main) {
                        onResult?.invoke(newPinned, null)
                    }
                } else {
                    val errMsg = (obj as? TdApi.Error)?.message ?: "Failed to toggle topic pin"
                    val reverted =
                        existing.map {
                            if (it.topicId == topicId) it.copy(isPinned = currentPinned) else it
                        }.sortedByDescending { it.isPinned }
                    forumTopicsCache[chatId] = reverted
                    scope.launch(Dispatchers.Main) {
                        onResult?.invoke(currentPinned, errMsg)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "toggleForumTopicPin failed: ${e.message}")
            val reverted =
                existing.map {
                    if (it.topicId == topicId) it.copy(isPinned = currentPinned) else it
                }.sortedByDescending { it.isPinned }
            forumTopicsCache[chatId] = reverted
            onResult?.invoke(currentPinned, e.message)
        }
    }

    fun toggleForumTopicPin(
        chatId: Long,
        topicId: Long,
        currentPinned: Boolean,
        onResult: (Boolean) -> Unit,
    ) {
        toggleForumTopicPin(chatId, topicId, currentPinned) { isPinned, _ ->
            onResult(isPinned)
        }
    }

    fun selectForumTopic(
        chatId: Long,
        topicId: Long,
        topicName: String,
    ) {
        synchronized(chatMap) {
            val existing = chatMap[chatId]
            if (existing != null) {
                chatMap[chatId] =
                    existing.copy(
                        isForum = true,
                        activeTopicId = topicId,
                        activeTopicName = if (topicId == 0L) "" else topicName,
                    )
            }
        }
        _chats.value = synchronized(chatMap) { chatMap.values.toList() }
    }

    fun getGroupMembers(chatId: Long): List<GroupMember> {
        val existing = groupMembersCache[chatId]
        if (existing != null) return existing
        val defaultList =
            listOf(
                GroupMember(userId = 1L, name = "You", role = "Owner", status = "online", isAdmin = true, isCreator = true),
                GroupMember(userId = 2L, name = "Alice Smith", role = "Admin", status = "online", isAdmin = true, isCreator = false),
                GroupMember(userId = 3L, name = "Bob Jones", role = "Member", status = "last seen recently", isAdmin = false, isCreator = false),
            )
        groupMembersCache[chatId] = defaultList
        return defaultList
    }

    fun setChatMemberAdminRole(
        chatId: Long,
        userId: Long,
        makeAdmin: Boolean,
        onResult: ((Boolean) -> Unit)? = null,
    ) {
        val current = getGroupMembers(chatId).toMutableList()
        val idx = current.indexOfFirst { it.userId == userId }
        if (idx >= 0) {
            val member = current[idx]
            current[idx] =
                member.copy(
                    isAdmin = makeAdmin,
                    role = if (makeAdmin) "Admin" else "Member",
                )
            groupMembersCache[chatId] = current
        }
        onResult?.invoke(true)
    }

    fun removeChatMember(
        chatId: Long,
        userId: Long,
        onResult: ((Boolean) -> Unit)? = null,
    ) {
        val current = getGroupMembers(chatId).filter { it.userId != userId }
        groupMembersCache[chatId] = current
        onResult?.invoke(true)
    }

    fun openSavedMessages(onResult: (ChatModel) -> Unit) {
        val existing =
            chatMap[777000L] ?: synchronized(chatMap) {
                chatMap.values.find {
                    it.title.equals("Saved Messages", ignoreCase = true) || it.id == 777000L
                }
            }
        if (existing != null && tdClient == null) {
            onResult(existing)
            return
        }

        val client = tdClient
        if (client == null) {
            val savedChat =
                ChatModel(
                    id = 777000L,
                    title = "Saved Messages",
                    lastMessage = "Cloud storage for notes & media",
                    time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
                    unreadCount = 0,
                    chatType = ChatType.DIRECT,
                    avatarLetters = "SM",
                    isPinned = true,
                )
            synchronized(chatMap) {
                chatMap[savedChat.id] = savedChat
                val updated = (listOf(savedChat) + chatMap.values.filter { it.id != savedChat.id }).sortedByDescending { it.isPinned }
                chatMap.clear()
                updated.forEach { chatMap[it.id] = it }
            }
            _chats.value = synchronized(chatMap) { chatMap.values.toList() }
            onResult(savedChat)
            return
        }

        scope.launch {
            try {
                val me = withTimeoutOrNull(4000L) { sendQuery(TdApi.GetMe()) } as? TdApi.User
                if (me != null) {
                    val chatObj =
                        withTimeoutOrNull(4000L) {
                            sendQuery(TdApi.CreatePrivateChat(me.id, false))
                        } as? TdApi.Chat
                    if (chatObj != null) {
                        val lastMsg = chatObj.lastMessage
                        val model =
                            ChatModel(
                                id = chatObj.id,
                                title = "Saved Messages",
                                lastMessage = extractMessageText(lastMsg?.content),
                                time = if (lastMsg != null) formatTime(lastMsg.date.toLong() * 1000) else "",
                                unreadCount = 0,
                                chatType = ChatType.DIRECT,
                                avatarLetters = "SM",
                                isPinned = true,
                            )
                        synchronized(chatMap) {
                            chatMap[model.id] = model
                            val updated = (listOf(model) + chatMap.values.filter { it.id != model.id }).sortedByDescending { it.isPinned }
                            chatMap.clear()
                            updated.forEach { chatMap[it.id] = it }
                        }
                        _chats.value = synchronized(chatMap) { chatMap.values.toList() }
                        withContext(Dispatchers.Main) { onResult(model) }
                        return@launch
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "openSavedMessages fallback: ${e.message}")
            }
            val fallback =
                existing
                    ?: ChatModel(
                        id = 777000L,
                        title = "Saved Messages",
                        lastMessage = "Cloud storage for notes & media",
                        time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
                        unreadCount = 0,
                        chatType = ChatType.DIRECT,
                        avatarLetters = "SM",
                        isPinned = true,
                    )
            try {
                withContext(Dispatchers.Main) { onResult(fallback) }
            } catch (e: Throwable) {
                onResult(fallback)
            }
        }
    }

    fun resolveAndCreateChat(
        query: String,
        onResult: (ChatModel?, String?) -> Unit,
    ) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            onResult(null, "Please enter a @username or phone number")
            return
        }

        val cleanUsername = trimmed.removePrefix("@").removePrefix("https://t.me/").trim()
        val matchedExisting =
            synchronized(chatMap) {
                chatMap.values.find {
                    it.title.equals(trimmed, ignoreCase = true) ||
                        it.title.equals(cleanUsername, ignoreCase = true)
                }
            }
        if (matchedExisting != null && tdClient == null) {
            onResult(matchedExisting, null)
            return
        }

        val client = tdClient
        if (client == null) {
            val isBot = cleanUsername.lowercase().endsWith("bot")
            val newChat =
                ChatModel(
                    id = kotlin.math.abs(cleanUsername.hashCode().toLong()).coerceAtLeast(1000L),
                    title = if (trimmed.startsWith("@")) "@$cleanUsername" else trimmed,
                    lastMessage = "New conversation",
                    time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
                    unreadCount = 0,
                    chatType = if (isBot) ChatType.BOT else ChatType.DIRECT,
                    avatarLetters = cleanUsername.take(2).uppercase().ifEmpty { "TG" },
                )
            synchronized(chatMap) {
                chatMap[newChat.id] = newChat
                val list = listOf(newChat) + chatMap.values.filter { it.id != newChat.id }
                chatMap.clear()
                list.forEach { chatMap[it.id] = it }
            }
            _chats.value = synchronized(chatMap) { chatMap.values.toList() }
            onResult(newChat, null)
            return
        }

        scope.launch {
            try {
                val isPhone = trimmed.startsWith("+") || trimmed.all { it.isDigit() || it == ' ' || it == '-' }
                val chatObj: TdApi.Chat? =
                    if (isPhone) {
                        val contact =
                            TdApi.ImportedContact().apply {
                                phoneNumber = trimmed
                                firstName = trimmed
                            }
                        val imported =
                            withTimeoutOrNull(5000L) {
                                sendQuery(TdApi.ImportContacts(arrayOf(contact)))
                            } as? TdApi.ImportedContacts
                        val userId = imported?.userIds?.firstOrNull() ?: 0L
                        if (userId != 0L) {
                            withTimeoutOrNull(5000L) {
                                sendQuery(TdApi.CreatePrivateChat(userId, false))
                            } as? TdApi.Chat
                        } else {
                            null
                        }
                    } else {
                        withTimeoutOrNull(5000L) {
                            sendQuery(TdApi.SearchPublicChat(cleanUsername))
                        } as? TdApi.Chat
                    }

                if (chatObj != null) {
                    addOrUpdateChat(chatObj)
                    val title = chatObj.title.ifBlank { trimmed }
                    val letters =
                        title.split(" ")
                            .filter { it.isNotBlank() }
                            .take(2)
                            .map { it.first().uppercaseChar() }
                            .joinToString("")
                            .ifEmpty { "TG" }
                    val createdModel =
                        ChatModel(
                            id = chatObj.id,
                            title = title,
                            lastMessage = extractMessageText(chatObj.lastMessage?.content),
                            time = "",
                            unreadCount = chatObj.unreadCount,
                            chatType = ChatType.DIRECT,
                            avatarLetters = letters,
                        )
                    withContext(Dispatchers.Main) { onResult(createdModel, null) }
                } else {
                    withContext(Dispatchers.Main) {
                        onResult(matchedExisting, if (matchedExisting == null) "User or chat not found: $trimmed" else null)
                    }
                }
            } catch (e: Exception) {
                try {
                    withContext(Dispatchers.Main) {
                        onResult(matchedExisting, e.message ?: "Failed to resolve chat")
                    }
                } catch (ex: Throwable) {
                    onResult(matchedExisting, e.message ?: "Failed to resolve chat")
                }
            }
        }
    }

    fun createGroupOrChannel(
        title: String,
        isChannel: Boolean,
        onResult: (ChatModel?, String?) -> Unit,
    ) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) {
            onResult(null, "Please enter a title for the ${if (isChannel) "channel" else "group"}")
            return
        }

        val letters =
            trimmed.split(" ")
                .filter { it.isNotBlank() }
                .take(2)
                .map { it.first().uppercaseChar() }
                .joinToString("")
                .ifEmpty { if (isChannel) "CH" else "GR" }

        val client = tdClient
        if (client == null) {
            val newChat =
                ChatModel(
                    id = kotlin.math.abs(trimmed.hashCode().toLong()).coerceAtLeast(2000L),
                    title = trimmed,
                    lastMessage = if (isChannel) "Channel created" else "Group created",
                    time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
                    unreadCount = 0,
                    chatType = if (isChannel) ChatType.CHANNEL else ChatType.GROUP,
                    avatarLetters = letters,
                )
            synchronized(chatMap) {
                chatMap[newChat.id] = newChat
                val list = listOf(newChat) + chatMap.values.filter { it.id != newChat.id }
                chatMap.clear()
                list.forEach { chatMap[it.id] = it }
            }
            _chats.value = synchronized(chatMap) { chatMap.values.toList() }
            onResult(newChat, null)
            return
        }

        scope.launch {
            try {
                val req: TdApi.Function<*> =
                    if (isChannel) {
                        TdApi.CreateNewSupergroupChat().apply {
                            this.title = trimmed
                            this.isChannel = true
                            this.description = ""
                        }
                    } else {
                        TdApi.CreateNewBasicGroupChat().apply {
                            this.userIds = longArrayOf()
                            this.title = trimmed
                        }
                    }
                val chatObj = withTimeoutOrNull(5000L) { sendQuery(req) } as? TdApi.Chat
                if (chatObj != null) {
                    addOrUpdateChat(chatObj)
                    val model =
                        ChatModel(
                            id = chatObj.id,
                            title = chatObj.title.ifBlank { trimmed },
                            lastMessage = if (isChannel) "Channel created" else "Group created",
                            time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
                            unreadCount = 0,
                            chatType = if (isChannel) ChatType.CHANNEL else ChatType.GROUP,
                            avatarLetters = letters,
                        )
                    withContext(Dispatchers.Main) { onResult(model, null) }
                } else {
                    withContext(Dispatchers.Main) { onResult(null, "Failed to create ${if (isChannel) "channel" else "group"}") }
                }
            } catch (e: Exception) {
                try {
                    withContext(Dispatchers.Main) { onResult(null, e.message) }
                } catch (ex: Throwable) {
                    onResult(null, e.message)
                }
            }
        }
    }

    fun sendVoiceNote(
        chatId: Long,
        filePath: String,
        durationSeconds: Int,
        disableNotification: Boolean = false,
        schedulingState: TdApi.MessageSchedulingState? = null,
    ) {
        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val newMsg =
            MessageModel(
                id = System.currentTimeMillis(),
                chatId = chatId,
                senderName = "You",
                text = "🎤 Voice Message (${durationSeconds}s)",
                time = timeStr,
                isOutbound = true,
                status = MessageStatus.SENT,
                mediaType = MediaType.VOICE,
                mediaFilePath = filePath,
                durationSeconds = durationSeconds,
            )

        appendMessage(chatId, newMsg)

        // Live TDLib Send Voice Note
        try {
            val inputVoice =
                TdApi.InputVoiceNote().apply {
                    this.voiceNote = TdApi.InputFileLocal(filePath)
                    this.duration = durationSeconds
                    this.waveform = byteArrayOf()
                }
            val voiceContent =
                TdApi.InputMessageVoiceNote().apply {
                    this.voiceNote = inputVoice
                }
            val sendReq =
                TdApi.SendMessage().apply {
                    this.chatId = chatId
                    this.inputMessageContent = voiceContent
                    this.options = createSendOptions(disableNotification, schedulingState)
                }
            tdClient?.send(sendReq, this)
        } catch (e: Exception) {
            Log.e(tag, "Failed to send voice note via TDLib: ${e.message}")
        }
    }

    fun recognizeSpeech(
        chatId: Long,
        messageId: Long,
        onResult: ((Boolean, String?) -> Unit)? = null,
    ) {
        scope.launch {
            try {
                val res = sendQuery(TdApi.RecognizeSpeech(chatId, messageId))
                if (res is TdApi.Ok) {
                    withContext(Dispatchers.Main) {
                        onResult?.invoke(true, null)
                    }
                } else if (res is TdApi.Error) {
                    val errMsg = res.message.ifBlank { "Speech recognition failed" }
                    withContext(Dispatchers.Main) {
                        onResult?.invoke(false, errMsg)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        onResult?.invoke(false, "Speech recognition failed")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult?.invoke(false, e.message ?: "Speech recognition error")
                }
            }
        }
    }

    fun updateMessageTranscript(
        chatId: Long,
        messageId: Long,
        transcriptText: String,
    ) {
        val currentMap = _messages.value.toMutableMap()
        val list = (currentMap[chatId] ?: return).toMutableList()
        val idx = list.indexOfFirst { it.id == messageId }
        if (idx >= 0) {
            list[idx] = list[idx].copy(transcript = transcriptText)
            currentMap[chatId] = list
            _messages.value = currentMap
        }
    }

    fun sendLocation(
        chatId: Long,
        latitude: Double,
        longitude: Double,
        accuracy: Float,
        disableNotification: Boolean = false,
        schedulingState: TdApi.MessageSchedulingState? = null,
    ) {
        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val latStr = String.format(Locale.US, "%.6f", latitude)
        val lngStr = String.format(Locale.US, "%.6f", longitude)
        val gmapsLink = "https://maps.google.com/?q=$latStr,$lngStr"
        val osmLink = "https://www.openstreetmap.org/?mlat=$latStr&mlon=$lngStr"

        val bodyText = "📍 Current GPS Location\nGoogle Maps: $gmapsLink\nOSM: $osmLink"

        val newMsg =
            MessageModel(
                id = System.currentTimeMillis(),
                chatId = chatId,
                senderName = "You",
                text = bodyText,
                time = timeStr,
                isOutbound = true,
                status = MessageStatus.SENT,
                mediaType = MediaType.LOCATION,
                latitude = latitude,
                longitude = longitude,
            )

        appendMessage(chatId, newMsg)

        // Live TDLib Send Location
        try {
            val locContent =
                TdApi.InputMessageLocation().apply {
                    this.location = TdApi.Location(latitude, longitude, accuracy.toDouble())
                }
            val sendReq =
                TdApi.SendMessage().apply {
                    this.chatId = chatId
                    this.inputMessageContent = locContent
                    this.options = createSendOptions(disableNotification, schedulingState)
                }
            tdClient?.send(sendReq, this)

            // Also send fallback text with Google Maps link for clients that don't render native geo points
            sendMessage(chatId, "📍 Location link: $gmapsLink", disableNotification = disableNotification, schedulingState = schedulingState)
        } catch (e: Exception) {
            Log.e(tag, "Failed to send location via TDLib: ${e.message}")
        }
    }

    fun updateMessageStatus(
        chatId: Long,
        messageId: Long,
        newStatus: MessageStatus,
    ) {
        val currentMap = _messages.value.toMutableMap()
        val chatMsgs = (currentMap[chatId] ?: emptyList()).toMutableList()
        val index = chatMsgs.indexOfFirst { it.id == messageId }
        if (index >= 0) {
            val oldMsg = chatMsgs[index]
            chatMsgs[index] = oldMsg.copy(status = newStatus)
            currentMap[chatId] = chatMsgs
            _messages.value = currentMap
        }
    }

    fun resendMessage(
        chatId: Long,
        messageId: Long,
    ) {
        val currentMap = _messages.value.toMutableMap()
        val chatMsgs = (currentMap[chatId] ?: emptyList()).toMutableList()
        val msg = chatMsgs.find { it.id == messageId } ?: return

        updateMessageStatus(chatId, messageId, MessageStatus.SENDING)

        if (_connectionState.value == "Offline" || _connectionState.value == "Waiting for network") {
            updateMessageStatus(chatId, messageId, MessageStatus.FAILED)
            return
        }

        // Live TDLib resend message
        try {
            val resendReq =
                TdApi.ResendMessages().apply {
                    this.chatId = chatId
                    this.messageIds = longArrayOf(messageId)
                }
            tdClient?.send(resendReq, this)
        } catch (e: Exception) {
            Log.e(tag, "Failed to resend message via TDLib: ${e.message}")
            if (msg.text.isNotBlank()) {
                sendMessage(chatId, msg.text)
            } else {
                updateMessageStatus(chatId, messageId, MessageStatus.FAILED)
            }
        }
    }

    private fun appendMessage(
        chatId: Long,
        message: MessageModel,
    ) {
        val currentMap = _messages.value.toMutableMap()
        val chatMsgs = (currentMap[chatId] ?: emptyList()).toMutableList()
        chatMsgs.add(message)
        currentMap[chatId] = chatMsgs
        _messages.value = currentMap

        synchronized(chatMap) {
            val existing = chatMap[chatId]
            if (existing != null) {
                chatMap[chatId] = existing.copy(lastMessage = message.text, time = message.time)
            }
        }
        _chats.value = synchronized(chatMap) { chatMap.values.toList() }
    }

    fun loadChats(limit: Int = 30) {
        tdClient?.send(TdApi.GetChats(TdApi.ChatListMain(), limit), this)
    }

    fun loadMoreChats(step: Int = 20) {
        val currentCount = synchronized(chatMap) { chatMap.size }
        tdClient?.send(TdApi.GetChats(TdApi.ChatListMain(), currentCount + step), this)
    }

    fun loadChatFolders() {
        tdClient?.send(TdApi.GetChatFolder(0), this)
    }

    private fun handleUpdateChatFolders(chatFolderInfos: Array<TdApi.ChatFolderInfo>?) {
        if (chatFolderInfos == null) return
        val currentFolders = _chatFolders.value.toMutableList()
        chatFolderInfos.forEach { info ->
            val folderName = info.name?.text?.text.orEmpty()
            if (folderName.isNotBlank()) {
                val idx = currentFolders.indexOfFirst { it.id == info.id || it.title.equals(folderName, ignoreCase = true) }
                if (idx < 0) {
                    currentFolders.add(ChatFolderModel(id = info.id, title = folderName, includedChatIds = emptySet()))
                }
            }
            tdClient?.send(TdApi.GetChatFolder(info.id), this)
        }
        _chatFolders.value = currentFolders
    }

    private fun handleChatFolderReceived(folder: TdApi.ChatFolder) {
        val title = folder.name?.text?.text.orEmpty()
        if (title.isBlank()) return
        val includedSet = folder.includedChatIds?.toSet() ?: emptySet()
        val currentFolders = _chatFolders.value.toMutableList()
        val idx = currentFolders.indexOfFirst { it.title.equals(title, ignoreCase = true) }
        if (idx >= 0) {
            currentFolders[idx] = currentFolders[idx].copy(includedChatIds = includedSet)
        } else {
            currentFolders.add(ChatFolderModel(id = currentFolders.size + 1, title = title, includedChatIds = includedSet))
        }
        _chatFolders.value = currentFolders

        if (includedSet.isNotEmpty()) {
            synchronized(chatMap) {
                for (id in includedSet) {
                    val chat = chatMap[id]
                    if (chat != null) {
                        chatMap[id] = chat.copy(folderName = title)
                    }
                }
            }
            _chats.value = synchronized(chatMap) { chatMap.values.toList() }
        }
    }

    fun loadMessages(
        chatId: Long,
        fromMessageId: Long = 0,
        limit: Int = 30,
    ) {
        tdClient?.send(TdApi.GetChatHistory(chatId, fromMessageId, 0, limit, false), this)
    }

    suspend fun getChatHistoryBatch(
        chatId: Long,
        fromMessageId: Long = 0,
        limit: Int = 100,
    ): List<TdApi.Message> {
        return try {
            val res = sendQuery(TdApi.GetChatHistory(chatId, fromMessageId, 0, limit, false))
            if (res is TdApi.Messages) {
                res.messages.toList()
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun resolveSenderName(sender: TdApi.MessageSender?): String {
        if (sender == null) return "Unknown"
        return when (sender) {
            is TdApi.MessageSenderUser -> {
                val cachedUser = userCache[sender.userId]
                if (cachedUser != null) {
                    val name = "${cachedUser.firstName} ${cachedUser.lastName}".trim()
                    if (name.isNotEmpty()) return name
                }
                try {
                    val res = sendQuery(TdApi.GetUser(sender.userId))
                    if (res is TdApi.User) {
                        userCache[sender.userId] = res
                        val name = "${res.firstName} ${res.lastName}".trim()
                        if (name.isNotEmpty()) name else "User ${sender.userId}"
                    } else {
                        "User ${sender.userId}"
                    }
                } catch (_: Exception) {
                    "User ${sender.userId}"
                }
            }
            is TdApi.MessageSenderChat -> {
                val cachedChat = chatMap[sender.chatId]
                if (cachedChat != null) return cachedChat.title
                try {
                    val res = sendQuery(TdApi.GetChat(sender.chatId))
                    if (res is TdApi.Chat) {
                        res.title
                    } else {
                        "Chat ${sender.chatId}"
                    }
                } catch (_: Exception) {
                    "Chat ${sender.chatId}"
                }
            }
            else -> "Unknown"
        }
    }

    fun getSenderDisplayName(message: TdApi.Message): String {
        if (message.isOutgoing) return "You"
        when (val sender = message.senderId) {
            is TdApi.MessageSenderUser -> {
                val user = userCache[sender.userId]
                if (user != null) {
                    val fullName = "${user.firstName} ${user.lastName.orEmpty()}".trim()
                    return if (fullName.isNotBlank()) fullName else "Contact"
                }
            }
            is TdApi.MessageSenderChat -> {
                val chat = chatMap[sender.chatId]
                if (chat != null) return chat.title
            }
        }
        return "Contact"
    }

    suspend fun downloadFileSuspending(
        fileId: Int,
        timeoutMs: Long = 30_000L,
    ): String? {
        if (fileId == 0) return null
        val client = tdClient ?: return null

        val currentFile =
            suspendCancellableCoroutine<TdApi.File?> { cont ->
                client.send(TdApi.GetFile(fileId)) { res ->
                    cont.resume(res as? TdApi.File)
                }
            }

        if (currentFile != null && currentFile.local.isDownloadingCompleted && currentFile.local.path.isNotBlank()) {
            return currentFile.local.path
        }

        val deferred = CompletableDeferred<String?>()
        val list = fileDownloadWaiters.computeIfAbsent(fileId) { CopyOnWriteArrayList() }
        list.add(deferred)

        try {
            client.send(TdApi.DownloadFile(fileId, 32, 0, 0, false)) { res ->
                val f = res as? TdApi.File
                if (f != null && f.local.isDownloadingCompleted && f.local.path.isNotBlank()) {
                    deferred.complete(f.local.path)
                }
            }
            return withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            list.remove(deferred)
            if (list.isEmpty()) {
                fileDownloadWaiters.remove(fileId)
            }
        }
    }

    suspend fun fetchAllCloudMessages(
        chatId: Long,
        onProgress: ((loadedCount: Int) -> Unit)? = null,
    ): List<TdApi.Message> {
        val allMessages = mutableListOf<TdApi.Message>()
        var fromMessageId = 0L
        val client = tdClient ?: return emptyList()

        while (true) {
            val batch =
                suspendCancellableCoroutine<Array<TdApi.Message>?> { cont ->
                    client.send(TdApi.GetChatHistory(chatId, fromMessageId, 0, 100, false)) { result ->
                        if (result is TdApi.Messages) {
                            cont.resume(result.messages)
                        } else {
                            cont.resume(null)
                        }
                    }
                }

            if (batch.isNullOrEmpty()) {
                break
            }

            allMessages.addAll(batch)
            onProgress?.invoke(allMessages.size)

            val oldest = batch.last()
            if (oldest.id == fromMessageId || batch.size < 100) {
                break
            }
            fromMessageId = oldest.id
        }

        return allMessages
    }

    suspend fun importChatHistoryFromCloud(
        chatId: Long,
        autoDownloadMedia: Boolean = true,
        onProgress: ((loadedMessages: Int, queuedMedia: Int) -> Unit)? = null,
    ): Pair<Int, Int> {
        val rawMessages =
            fetchAllCloudMessages(chatId) { count ->
                onProgress?.invoke(count, 0)
            }

        val convertedList =
            rawMessages.map { msg ->
                val mediaInfo = extractMediaFileInfo(msg.content)
                val stickerInfo = extractStickerInfo(msg.content)
                MessageModel(
                    id = msg.id,
                    chatId = msg.chatId,
                    senderName = getSenderDisplayName(msg),
                    text = extractMessageText(msg.content),
                    time = formatTime(msg.date.toLong() * 1000),
                    isOutbound = msg.isOutgoing,
                    status = MessageStatus.READ,
                    mediaType = extractMediaType(msg.content),
                    mediaCaption = extractMediaCaption(msg.content),
                    mediaFilePath = mediaInfo.second,
                    mediaFileId = mediaInfo.first,
                    stickerEmoji = stickerInfo.first,
                    isPremiumSticker = stickerInfo.second,
                    transcript = extractVoiceTranscript(msg.content),
                )
            }

        withContext(Dispatchers.Main) {
            val currentMap = _messages.value.toMutableMap()
            val existing = currentMap[chatId] ?: emptyList()
            val merged = (convertedList + existing).distinctBy { it.id }.sortedBy { it.id }
            currentMap[chatId] = merged
            _messages.value = currentMap
        }

        var queuedMediaCount = 0
        if (autoDownloadMedia) {
            for (msg in rawMessages) {
                val mediaInfo = extractMediaFileInfo(msg.content)
                if (mediaInfo.first != 0 && mediaInfo.second.isNullOrBlank()) {
                    tdClient?.send(TdApi.DownloadFile(mediaInfo.first, 1, 0, 0, false), this)
                    queuedMediaCount++
                    onProgress?.invoke(rawMessages.size, queuedMediaCount)
                }
            }
        }

        return Pair(rawMessages.size, queuedMediaCount)
    }

    suspend fun exportChatHistory(
        chatId: Long,
        chatTitle: String,
        options: ChatExportOptions,
        onProgress: ((ChatExportProgress) -> Unit)? = null,
    ): Result<File> {
        onProgress?.invoke(
            ChatExportProgress(
                stage = com.teleflip.app.storage.ExportStage.FETCHING_CLOUD_HISTORY,
                statusText = "Syncing complete cloud history for \"$chatTitle\"...",
            ),
        )

        val rawMessages =
            if (tdClient != null) {
                fetchAllCloudMessages(chatId) { count ->
                    onProgress?.invoke(
                        ChatExportProgress(
                            stage = com.teleflip.app.storage.ExportStage.FETCHING_CLOUD_HISTORY,
                            messagesProcessed = count,
                            totalMessages = count,
                            statusText = "Loaded $count cloud messages...",
                        ),
                    )
                }
            } else {
                emptyList()
            }

        val messagesToExport =
            if (rawMessages.isNotEmpty()) {
                rawMessages.reversed().map { msg ->
                    val mediaInfo = extractMediaFileInfo(msg.content)
                    val stickerInfo = extractStickerInfo(msg.content)
                    MessageModel(
                        id = msg.id,
                        chatId = msg.chatId,
                        senderName = getSenderDisplayName(msg),
                        text = extractMessageText(msg.content),
                        time = formatTime(msg.date.toLong() * 1000),
                        isOutbound = msg.isOutgoing,
                        status = MessageStatus.READ,
                        mediaType = extractMediaType(msg.content),
                        mediaCaption = extractMediaCaption(msg.content),
                        mediaFilePath = mediaInfo.second,
                        mediaFileId = mediaInfo.first,
                        stickerEmoji = stickerInfo.first,
                        isPremiumSticker = stickerInfo.second,
                        transcript = extractVoiceTranscript(msg.content),
                    )
                }
            } else {
                _messages.value[chatId] ?: emptyList()
            }

        return ChatExportManager.exportChat(
            context = context,
            chatId = chatId,
            chatTitle = chatTitle,
            messages = messagesToExport,
            options = options,
            fileDownloader = { fileId -> downloadFileSuspending(fileId) },
            onProgress = onProgress,
        )
    }

    fun searchChats(
        query: String,
        onResult: (List<ChatModel>) -> Unit,
    ) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            onResult(emptyList())
            return
        }
        val lowerQuery = trimmed.lowercase()
        val localMatched =
            synchronized(chatMap) {
                chatMap.values.filter {
                    it.title.lowercase().contains(lowerQuery) || it.lastMessage.lowercase().contains(lowerQuery)
                }
            }
        val client = tdClient
        if (client == null) {
            onResult(localMatched)
            return
        }

        val req = TdApi.SearchChats(trimmed, null, 20)
        client.send(req) { obj ->
            if (obj is TdApi.Chats) {
                for (id in obj.chatIds) {
                    tdClient?.send(TdApi.GetChat(id), this)
                }
                scope.launch(Dispatchers.Main) {
                    val updatedList =
                        synchronized(chatMap) {
                            chatMap.values.filter {
                                it.title.lowercase().contains(lowerQuery) || it.lastMessage.lowercase().contains(lowerQuery)
                            }
                        }
                    val combined = (localMatched + updatedList).distinctBy { it.id }
                    onResult(combined.take(50))
                }
            } else {
                scope.launch(Dispatchers.Main) {
                    onResult(localMatched)
                }
            }
        }
    }

    fun searchContacts(
        query: String,
        onResult: (List<ChatModel>) -> Unit,
    ) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            onResult(emptyList())
            return
        }
        val lowerQuery = trimmed.lowercase()
        val localContacts =
            synchronized(chatMap) {
                chatMap.values.filter {
                    it.chatType == ChatType.DIRECT && (it.title.lowercase().contains(lowerQuery) || it.lastMessage.lowercase().contains(lowerQuery))
                }
            }
        val client = tdClient
        if (client == null) {
            onResult(localContacts)
            return
        }

        val req = TdApi.SearchContacts(trimmed, 20)
        client.send(req) { obj ->
            if (obj is TdApi.Users) {
                scope.launch(Dispatchers.Main) {
                    val combined = localContacts.toMutableList()
                    onResult(combined.take(50))
                }
            } else {
                scope.launch(Dispatchers.Main) {
                    onResult(localContacts)
                }
            }
        }
    }

    fun searchMessages(
        query: String,
        offset: String = "",
        limit: Int = 20,
        onResult: (totalCount: Int, results: List<MessageSearchResult>, nextOffset: String) -> Unit,
    ) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            onResult(0, emptyList(), "")
            return
        }

        val client = tdClient
        if (client == null) {
            val lowerQuery = trimmed.lowercase()
            val results = mutableListOf<MessageSearchResult>()
            for ((cId, msgList) in _messages.value) {
                val chatTitle = chatMap[cId]?.title ?: "Chat #$cId"
                for (msg in msgList) {
                    if (msg.text.lowercase().contains(lowerQuery) || msg.mediaCaption.lowercase().contains(lowerQuery)) {
                        results.add(
                            MessageSearchResult(
                                messageId = msg.id,
                                chatId = msg.chatId,
                                chatTitle = chatTitle,
                                senderName = msg.senderName,
                                text = msg.text.ifBlank { msg.mediaCaption },
                                time = msg.time,
                            ),
                        )
                    }
                }
            }
            val maxResults = results.take(50)
            onResult(maxResults.size, maxResults, "")
            return
        }

        val req =
            TdApi.SearchMessages().apply {
                this.chatList = TdApi.ChatListMain()
                this.query = trimmed
                this.offset = offset
                this.limit = limit.coerceAtMost(50)
                this.filter = null
                this.chatTypeFilter = null
                this.minDate = 0
                this.maxDate = 0
            }

        client.send(req) { obj ->
            if (obj is TdApi.FoundMessages) {
                val nextOff = obj.nextOffset
                val total = obj.totalCount
                val list = mutableListOf<MessageSearchResult>()
                for (msg in obj.messages) {
                    val cId = msg.chatId
                    val mId = msg.id
                    val text = extractMessageText(msg.content)
                    val time = formatTime(msg.date.toLong() * 1000)
                    val chatTitle = chatMap[cId]?.title ?: "Chat #$cId"
                    val sender = if (msg.isOutgoing) "You" else "Contact"

                    list.add(
                        MessageSearchResult(
                            messageId = mId,
                            chatId = cId,
                            chatTitle = chatTitle,
                            senderName = sender,
                            text = text,
                            time = time,
                            dateMillis = msg.date.toLong() * 1000,
                        ),
                    )
                }
                scope.launch(Dispatchers.Main) {
                    onResult(total, list.take(50), nextOff)
                }
            } else {
                scope.launch(Dispatchers.Main) {
                    onResult(0, emptyList(), "")
                }
            }
        }
    }

    // =========================================================================
    // Telegram API ToS §1.4 Compliance: Chat open/close lifecycle
    // Required so that last-seen/online statuses are reported correctly
    // =========================================================================
    fun openChat(chatId: Long) {
        tdClient?.send(TdApi.OpenChat(chatId), this)
    }

    fun closeChat(chatId: Long) {
        tdClient?.send(TdApi.CloseChat(chatId), this)
    }

    // =========================================================================
    // In-Chat Message Search
    // =========================================================================
    fun searchChatMessages(
        chatId: Long,
        query: String,
        onResult: (List<MessageSearchResult>) -> Unit,
    ) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            onResult(emptyList())
            return
        }

        val client = tdClient
        if (client == null) {
            val lower = trimmed.lowercase()
            val localMsgs = _messages.value[chatId].orEmpty()
            val matches =
                localMsgs.filter {
                    it.text.lowercase().contains(lower) || it.mediaCaption.lowercase().contains(lower)
                }.map {
                    MessageSearchResult(
                        messageId = it.id,
                        chatId = it.chatId,
                        chatTitle = chatMap[chatId]?.title ?: "Chat",
                        senderName = it.senderName,
                        text = it.text.ifBlank { it.mediaCaption },
                        time = it.time,
                    )
                }
            onResult(matches)
            return
        }

        val req =
            TdApi.SearchChatMessages().apply {
                this.chatId = chatId
                this.query = trimmed
                this.fromMessageId = 0L
                this.offset = 0
                this.limit = 50
                this.filter = null
            }

        client.send(req) { obj ->
            if (obj is TdApi.FoundChatMessages) {
                val list = mutableListOf<MessageSearchResult>()
                val chatTitle = chatMap[chatId]?.title ?: "Chat"
                for (msg in obj.messages) {
                    val text = extractMessageText(msg.content)
                    val time = formatTime(msg.date.toLong() * 1000)
                    val sender = if (msg.isOutgoing) "You" else "Contact"
                    list.add(
                        MessageSearchResult(
                            messageId = msg.id,
                            chatId = chatId,
                            chatTitle = chatTitle,
                            senderName = sender,
                            text = text,
                            time = time,
                            dateMillis = msg.date.toLong() * 1000,
                        ),
                    )
                }
                scope.launch(Dispatchers.Main) {
                    onResult(list)
                }
            } else {
                scope.launch(Dispatchers.Main) {
                    onResult(emptyList())
                }
            }
        }
    }

    // =========================================================================
    // Chat History Management (Clear History)
    // =========================================================================
    fun deleteChatHistory(
        chatId: Long,
        revoke: Boolean = true,
        onComplete: (() -> Unit)? = null,
    ) {
        val client = tdClient
        if (client != null) {
            val req =
                TdApi.DeleteChatHistory().apply {
                    this.chatId = chatId
                    this.removeFromChatList = false
                    this.revoke = revoke
                }
            client.send(req) {
                scope.launch(Dispatchers.Main) {
                    val currentMap = _messages.value.toMutableMap()
                    currentMap[chatId] = emptyList()
                    _messages.value = currentMap
                    onComplete?.invoke()
                }
            }
        } else {
            val currentMap = _messages.value.toMutableMap()
            currentMap[chatId] = emptyList()
            _messages.value = currentMap
            onComplete?.invoke()
        }
    }

    // =========================================================================
    // Contact Management
    // =========================================================================
    fun loadContacts(onResult: (List<ContactModel>) -> Unit) {
        val client = tdClient
        if (client == null) {
            val fallbackContacts =
                synchronized(chatMap) {
                    chatMap.values.filter { it.chatType == ChatType.DIRECT }.map {
                        ContactModel(
                            userId = it.id,
                            firstName = it.title,
                            lastName = "",
                            phoneNumber = "",
                            username = "",
                            isOnline = false,
                        )
                    }
                }
            onResult(fallbackContacts)
            return
        }

        client.send(TdApi.GetContacts()) { obj ->
            if (obj is TdApi.Users) {
                val userIds = obj.userIds
                if (userIds.isEmpty()) {
                    scope.launch(Dispatchers.Main) { onResult(emptyList()) }
                    return@send
                }
                val contacts = mutableListOf<ContactModel>()
                val latch = java.util.concurrent.atomic.AtomicInteger(userIds.size)
                for (uid in userIds) {
                    client.send(TdApi.GetUser(uid)) { uObj ->
                        if (uObj is TdApi.User) {
                            val isOnline = uObj.status is TdApi.UserStatusOnline
                            contacts.add(
                                ContactModel(
                                    userId = uObj.id,
                                    firstName = uObj.firstName,
                                    lastName = uObj.lastName,
                                    phoneNumber = uObj.phoneNumber,
                                    username = uObj.usernames?.activeUsernames?.firstOrNull() ?: "",
                                    isOnline = isOnline,
                                ),
                            )
                        }
                        if (latch.decrementAndGet() == 0) {
                            val sorted = contacts.sortedBy { "${it.firstName} ${it.lastName}".trim().lowercase() }
                            scope.launch(Dispatchers.Main) { onResult(sorted) }
                        }
                    }
                }
            } else {
                scope.launch(Dispatchers.Main) { onResult(emptyList()) }
            }
        }
    }

    fun addContact(
        phoneNumber: String,
        firstName: String,
        lastName: String,
        onResult: (Boolean, String?) -> Unit,
    ) {
        val client = tdClient
        if (client == null) {
            onResult(false, "Client not connected")
            return
        }
        val cleanPhone = org.telegram.PhoneFormat.PhoneFormat.stripExceptNumbers(phoneNumber, true) ?: phoneNumber
        val normalized = if (!cleanPhone.startsWith("+")) "+$cleanPhone" else cleanPhone
        val newContact =
            TdApi.ImportedContact().apply {
                this.phoneNumber = normalized
                this.firstName = firstName
                this.lastName = lastName
            }
        val req =
            TdApi.ImportContacts().apply {
                this.contacts = arrayOf(newContact)
            }
        client.send(req) { obj ->
            scope.launch(Dispatchers.Main) {
                if (obj is TdApi.ImportedContacts) {
                    onResult(true, null)
                } else if (obj is TdApi.Error) {
                    onResult(false, obj.message)
                } else {
                    onResult(false, "Failed to add contact")
                }
            }
        }
    }

    // =========================================================================
    // User Profile Management (View & Edit Profile)
    // =========================================================================
    fun loadCurrentUser(onResult: ((UserProfileModel?) -> Unit)? = null) {
        val client = tdClient ?: return
        client.send(TdApi.GetMe()) { meObj ->
            if (meObj is TdApi.User) {
                client.send(TdApi.GetUserFullInfo(meObj.id)) { infoObj ->
                    val bioText = if (infoObj is TdApi.UserFullInfo) infoObj.bio?.text.orEmpty() else ""
                    val model =
                        UserProfileModel(
                            userId = meObj.id,
                            firstName = meObj.firstName,
                            lastName = meObj.lastName,
                            phoneNumber = meObj.phoneNumber,
                            username = meObj.usernames?.activeUsernames?.firstOrNull() ?: "",
                            bio = bioText,
                        )
                    _currentUser.value = model
                    scope.launch(Dispatchers.Main) {
                        onResult?.invoke(model)
                    }
                }
            } else {
                scope.launch(Dispatchers.Main) {
                    onResult?.invoke(null)
                }
            }
        }
    }

    fun updateProfile(
        firstName: String,
        lastName: String,
        bio: String,
        username: String,
        onResult: (Boolean, String?) -> Unit,
    ) {
        val client = tdClient
        if (client == null) {
            onResult(false, "Client not connected")
            return
        }
        scope.launch {
            try {
                sendQuery(
                    TdApi.SetName().apply {
                        this.firstName = firstName
                        this.lastName = lastName
                    },
                )
                sendQuery(
                    TdApi.SetBio().apply {
                        this.bio = bio
                    },
                )
                if (username.isNotBlank()) {
                    val cleanUsername = username.removePrefix("@").trim()
                    sendQuery(
                        TdApi.SetUsername().apply {
                            this.username = cleanUsername
                        },
                    )
                }
                loadCurrentUser()
                withContext(Dispatchers.Main) {
                    onResult(true, null)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(false, e.message)
                }
            }
        }
    }

    // =========================================================================
    // Group Administration: Add Member
    // =========================================================================
    fun addChatMember(
        chatId: Long,
        userId: Long,
        onResult: (Boolean, String?) -> Unit,
    ) {
        val client = tdClient
        if (client == null) {
            onResult(false, "Not connected")
            return
        }
        val req =
            TdApi.AddChatMember().apply {
                this.chatId = chatId
                this.userId = userId
                this.forwardLimit = 50
            }
        client.send(req) { obj ->
            scope.launch(Dispatchers.Main) {
                if (obj is TdApi.Ok) {
                    onResult(true, null)
                } else if (obj is TdApi.Error) {
                    onResult(false, obj.message)
                } else {
                    onResult(false, "Unable to add member")
                }
            }
        }
    }

    // =========================================================================
    // Active Sessions & Devices Security
    // =========================================================================
    fun loadActiveSessions(onResult: (List<SessionModel>) -> Unit) {
        val client = tdClient
        if (client == null) {
            onResult(emptyList())
            return
        }
        client.send(TdApi.GetActiveSessions()) { obj ->
            if (obj is TdApi.Sessions) {
                val list =
                    obj.sessions.map { s ->
                        val dateStr = formatTime(s.lastActiveDate.toLong() * 1000)
                        SessionModel(
                            id = s.id,
                            isCurrent = s.isCurrent,
                            deviceModel = s.deviceModel,
                            platform = s.platform,
                            systemVersion = s.systemVersion,
                            appName = s.applicationName,
                            appVersion = s.applicationVersion,
                            ip = s.ipAddress,
                            country = s.location,
                            activeDate = dateStr,
                        )
                    }
                scope.launch(Dispatchers.Main) {
                    onResult(list)
                }
            } else {
                scope.launch(Dispatchers.Main) {
                    onResult(emptyList())
                }
            }
        }
    }

    fun terminateAllOtherSessions(onResult: (Boolean, String?) -> Unit) {
        val client = tdClient
        if (client == null) {
            onResult(false, "Not connected")
            return
        }
        client.send(TdApi.TerminateAllOtherSessions()) { obj ->
            scope.launch(Dispatchers.Main) {
                if (obj is TdApi.Ok) {
                    onResult(true, null)
                } else if (obj is TdApi.Error) {
                    onResult(false, obj.message)
                } else {
                    onResult(false, "Failed to terminate sessions")
                }
            }
        }
    }

    // =========================================================================
    // Storage & Media Cache Cleaner
    // =========================================================================
    fun clearMediaCache(
        ttlDays: Int = 0,
        onResult: (Boolean, Long) -> Unit,
    ) {
        scope.launch(Dispatchers.IO) {
            var freedBytes = 0L
            try {
                val cacheDir = context.cacheDir
                cacheDir?.listFiles()?.forEach { file ->
                    freedBytes += file.length()
                    file.deleteRecursively()
                }
                val extCache = context.externalCacheDir
                extCache?.listFiles()?.forEach { file ->
                    freedBytes += file.length()
                    file.deleteRecursively()
                }
                val client = tdClient
                if (client != null) {
                    val ttlSec =
                        if (ttlDays > 0) {
                            ttlDays * com.teleflip.app.storage.StorageManager.SECONDS_PER_DAY
                        } else {
                            -1
                        }
                    val req =
                        TdApi.OptimizeStorage().apply {
                            this.size = -1L
                            this.ttl = ttlSec
                            this.count = -1
                            this.immunityDelay = -1
                        }
                    client.send(req) {}
                }
                withContext(Dispatchers.Main) {
                    onResult(true, freedBytes)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(false, 0L)
                }
            }
        }
    }

    fun switchStorageLocation(
        newType: com.teleflip.app.storage.StorageLocationType,
        onComplete: (Boolean, String) -> Unit,
    ) {
        val oldDir = com.teleflip.app.storage.StorageManager.getFilesDirectory(context)
        val success = com.teleflip.app.storage.StorageManager.setSelectedStorageType(context, newType)
        if (!success) {
            onComplete(false, "Storage location is not available or not mounted")
            return
        }
        val newDir = com.teleflip.app.storage.StorageManager.getFilesDirectory(context)
        scope.launch(Dispatchers.IO) {
            val copied = com.teleflip.app.storage.StorageManager.copyMediaFiles(oldDir, newDir)
            val label =
                if (newType == com.teleflip.app.storage.StorageLocationType.SD_CARD) {
                    "MicroSD Card"
                } else {
                    "Internal Storage"
                }
            withContext(Dispatchers.Main) {
                onComplete(true, "Storage switched to $label. Migrated $copied files.")
            }
        }
    }

    // =========================================================================
    // Telegram API ToS §1.4 Compliance: Read receipts
    // Messages must be marked as read when viewed — no "ghost mode"
    // =========================================================================
    fun markMessagesAsRead(
        chatId: Long,
        messageIds: LongArray,
    ) {
        if (messageIds.isEmpty()) return
        tdClient?.send(TdApi.ViewMessages(chatId, messageIds, null, true), this)
    }

    // =========================================================================
    // Telegram API ToS §1.4 Compliance: Typing indicator
    // Typing status must be sent and displayed correctly
    // =========================================================================
    fun sendTypingAction(chatId: Long) {
        try {
            val req =
                TdApi.SendChatAction().apply {
                    this.chatId = chatId
                    this.action = TdApi.ChatActionTyping()
                }
            tdClient?.send(req, this)
        } catch (e: Exception) {
            Log.w(tag, "sendTypingAction failed: ${e.message}")
        }
    }

    fun cancelTypingAction(chatId: Long) {
        try {
            val req =
                TdApi.SendChatAction().apply {
                    this.chatId = chatId
                    this.action = TdApi.ChatActionCancel()
                }
            tdClient?.send(req, this)
        } catch (e: Exception) {
            Log.w(tag, "cancelTypingAction failed: ${e.message}")
        }
    }

    // =========================================================================
    // Telegram API ToS §3.3 Compliance: Sponsored messages
    // If channel content is shown, official sponsored messages must be included
    // =========================================================================
    fun loadSponsoredMessages(chatId: Long) {
        try {
            tdClient?.send(TdApi.GetChatSponsoredMessages(chatId), this)
        } catch (e: Exception) {
            Log.w(tag, "Sponsored messages not available in this TDLib version: ${e.message}")
        }
    }

    fun clickSponsoredMessage(
        chatId: Long,
        messageId: Long,
    ) {
        try {
            val req =
                TdApi.ClickChatSponsoredMessage().apply {
                    this.chatId = chatId
                    this.messageId = messageId
                }
            tdClient?.send(req, this)
        } catch (e: Exception) {
            Log.w(tag, "Click sponsored message failed: ${e.message}")
        }
    }

    fun getUserPhoneNumber(userId: Long): String? = userPhoneCache[userId]

    fun getDirectChatUserId(chatId: Long): Long? = chatIdToUserId[chatId] ?: if (chatId > 0) chatId else null

    fun forwardMessage(
        fromChatId: Long,
        toChatId: Long,
        messageId: Long,
        onComplete: ((Boolean) -> Unit)? = null,
    ) {
        val client = tdClient
        if (client == null) {
            onComplete?.invoke(false)
            return
        }

        try {
            val req =
                TdApi.ForwardMessages().apply {
                    this.chatId = toChatId
                    this.fromChatId = fromChatId
                    this.messageIds = longArrayOf(messageId)
                }
            client.send(req) { result ->
                val success = result is TdApi.Messages
                scope.launch(Dispatchers.Main) {
                    onComplete?.invoke(success)
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to forward message: ${e.message}")
            onComplete?.invoke(false)
        }
    }

    fun downloadFilePriority(fileId: Int) {
        if (fileId == 0) return
        try {
            tdClient?.send(TdApi.DownloadFile(fileId, 32, 0, 0, false), this)
        } catch (e: Exception) {
            Log.e(tag, "Failed to download file priority: ${e.message}")
        }
    }

    fun initiateTelegramCall(
        userId: Long,
        onResult: ((Boolean, String?) -> Unit)? = null,
    ) {
        val userName =
            userCache[userId]?.let { u ->
                if (!u.lastName.isNullOrBlank()) "${u.firstName} ${u.lastName}" else u.firstName
            } ?: chatMap[userId]?.title ?: "Telegram Call"

        val client = tdClient
        if (client == null) {
            onResult?.invoke(false, "TDLib client is not available")
            return
        }
        try {
            val protocol = TdApi.CallProtocol(true, true, 65, 92, arrayOf("libtgvoip"))
            val req = TdApi.CreateCall(userId, protocol, false)
            client.send(req) { result ->
                scope.launch(Dispatchers.Main) {
                    if (result is TdApi.CallId) {
                        _activeCall.value =
                            ActiveCall(
                                callId = result.id,
                                userId = userId,
                                userName = userName,
                                isOutgoing = true,
                                state = CallState.PENDING,
                                durationSec = 0,
                            )
                        onResult?.invoke(true, "Call initiated (ID: ${result.id})")
                    } else if (result is TdApi.Error) {
                        onResult?.invoke(false, result.message)
                    } else {
                        onResult?.invoke(false, "Call initiation returned unexpected result")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to create call: ${e.message}")
            onResult?.invoke(false, e.message)
        }
    }

    fun acceptCall(
        callId: Int,
        onResult: ((Boolean, String?) -> Unit)? = null,
    ) {
        val client = tdClient
        if (client == null) {
            onResult?.invoke(false, "TDLib client is not available")
            return
        }
        val protocol = TdApi.CallProtocol(true, true, 65, 92, arrayOf("libtgvoip"))
        val req =
            TdApi.AcceptCall().apply {
                this.callId = callId
                this.protocol = protocol
            }
        client.send(req) { result ->
            scope.launch(Dispatchers.Main) {
                if (result is TdApi.Ok) {
                    _activeCall.value = _activeCall.value?.copy(state = CallState.EXCHANGING_KEYS)
                    onResult?.invoke(true, "Call accepted")
                } else if (result is TdApi.Error) {
                    onResult?.invoke(false, result.message)
                } else {
                    onResult?.invoke(false, "Failed to accept call")
                }
            }
        }
    }

    fun discardCall(
        callId: Int,
        durationSec: Int = 0,
        onResult: ((Boolean, String?) -> Unit)? = null,
    ) {
        val client = tdClient
        if (client == null) {
            _activeCall.value = _activeCall.value?.copy(state = CallState.DISCARDED)
            scope.launch {
                delay(1200L)
                _activeCall.value = null
            }
            onResult?.invoke(true, "Call discarded")
            return
        }
        val req =
            TdApi.DiscardCall().apply {
                this.callId = callId
                this.isDisconnected = false
                this.duration = durationSec
                this.isVideo = false
                this.connectionId = 0L
            }
        client.send(req) { result ->
            scope.launch(Dispatchers.Main) {
                _activeCall.value = _activeCall.value?.copy(state = CallState.DISCARDED)
                scope.launch {
                    delay(1200L)
                    _activeCall.value = null
                }
                if (result is TdApi.Ok) {
                    onResult?.invoke(true, "Call discarded")
                } else if (result is TdApi.Error) {
                    onResult?.invoke(false, result.message)
                }
            }
        }
    }

    fun updateCallAudioState(
        isMuted: Boolean? = null,
        isSpeakerOn: Boolean? = null,
    ) {
        val curr = _activeCall.value ?: return
        _activeCall.value =
            curr.copy(
                isMuted = isMuted ?: curr.isMuted,
                isSpeakerOn = isSpeakerOn ?: curr.isSpeakerOn,
            )
    }

    fun updateCallDuration(durationSec: Int) {
        val curr = _activeCall.value ?: return
        _activeCall.value = curr.copy(durationSec = durationSec)
    }

    fun handleCallUpdate(call: TdApi.Call) {
        val state =
            when (call.state.constructor) {
                TdApi.CallStatePending.CONSTRUCTOR -> CallState.PENDING
                TdApi.CallStateExchangingKeys.CONSTRUCTOR -> CallState.EXCHANGING_KEYS
                TdApi.CallStateReady.CONSTRUCTOR -> CallState.READY
                TdApi.CallStateHangingUp.CONSTRUCTOR -> CallState.HANGING_UP
                TdApi.CallStateDiscarded.CONSTRUCTOR -> CallState.DISCARDED
                TdApi.CallStateError.CONSTRUCTOR -> CallState.ERROR
                else -> CallState.PENDING
            }

        val userName =
            userCache[call.userId]?.let { u ->
                if (!u.lastName.isNullOrBlank()) "${u.firstName} ${u.lastName}" else u.firstName
            } ?: chatMap[call.userId]?.title ?: "Telegram Call"

        val existing = _activeCall.value
        val updated =
            ActiveCall(
                callId = call.id,
                userId = call.userId,
                userName = userName,
                isOutgoing = call.isOutgoing,
                state = state,
                durationSec = existing?.durationSec ?: 0,
                isMuted = existing?.isMuted ?: false,
                isSpeakerOn = existing?.isSpeakerOn ?: false,
            )
        _activeCall.value = updated
        if (!call.isOutgoing && state == CallState.PENDING && backgroundPushEnabled) {
            NotificationHelper.showIncomingCallNotification(context, call.id, userName)
        }

        if (state == CallState.DISCARDED || state == CallState.ERROR) {
            scope.launch {
                delay(1500L)
                if (_activeCall.value?.callId == call.id) {
                    _activeCall.value = null
                }
            }
        }
    }

    fun loadChatProfile(
        chatId: Long,
        onResult: (ChatProfile) -> Unit,
    ) {
        val chatModel = chatMap[chatId]

        scope.launch {
            try {
                val chatObj =
                    withTimeoutOrNull(4000L) {
                        sendQuery(TdApi.GetChat(chatId))
                    } as? TdApi.Chat

                val title = chatObj?.title ?: chatModel?.title ?: "Chat"
                val chatType = chatModel?.chatType ?: ChatType.DIRECT
                val avatarPath = chatModel?.avatarPath
                val avatarLetters = chatModel?.avatarLetters ?: "TG"
                val isPremium = chatModel?.isPremium ?: false
                val isMuted =
                    chatObj?.notificationSettings?.let {
                        isNotificationSettingsMuted(it, chatType)
                    } ?: isChatMuted(chatId)

                var username = ""
                var phone = ""
                var bio = ""
                var memberCount = 0
                var statusText = ""

                if (chatObj?.type is TdApi.ChatTypePrivate) {
                    val userId = (chatObj.type as TdApi.ChatTypePrivate).userId
                    val userObj =
                        withTimeoutOrNull(3000L) {
                            sendQuery(TdApi.GetUser(userId))
                        } as? TdApi.User
                    val fullInfo =
                        withTimeoutOrNull(3000L) {
                            sendQuery(TdApi.GetUserFullInfo(userId))
                        } as? TdApi.UserFullInfo

                    if (userObj != null) {
                        username = userObj.usernames?.activeUsernames?.firstOrNull()?.let { "@$it" } ?: ""
                        phone = userObj.phoneNumber ?: ""
                        statusText =
                            when (userObj.status?.constructor) {
                                TdApi.UserStatusOnline.CONSTRUCTOR -> "Online"
                                TdApi.UserStatusOffline.CONSTRUCTOR -> "Offline"
                                TdApi.UserStatusRecently.CONSTRUCTOR -> "Last seen recently"
                                else -> "Offline"
                            }
                    }
                    bio = fullInfo?.bio?.text ?: ""
                } else if (chatObj?.type is TdApi.ChatTypeBasicGroup) {
                    val basicGroupId = (chatObj.type as TdApi.ChatTypeBasicGroup).basicGroupId
                    val groupFull =
                        withTimeoutOrNull(3000L) {
                            sendQuery(TdApi.GetBasicGroupFullInfo(basicGroupId))
                        } as? TdApi.BasicGroupFullInfo
                    bio = groupFull?.description ?: ""
                    memberCount = groupFull?.members?.size ?: 0
                    statusText = "$memberCount members"
                } else if (chatObj?.type is TdApi.ChatTypeSupergroup) {
                    val supergroupId = (chatObj.type as TdApi.ChatTypeSupergroup).supergroupId
                    val supergroupFull =
                        withTimeoutOrNull(3000L) {
                            sendQuery(TdApi.GetSupergroupFullInfo(supergroupId))
                        } as? TdApi.SupergroupFullInfo
                    bio = supergroupFull?.description ?: ""
                    memberCount = supergroupFull?.memberCount ?: 0
                    statusText = if (chatType == ChatType.CHANNEL) "$memberCount subscribers" else "$memberCount members"
                    if (!supergroupFull?.inviteLink?.inviteLink.isNullOrBlank()) {
                        username = supergroupFull?.inviteLink?.inviteLink ?: ""
                    }
                }

                val membersList =
                    if (chatType == ChatType.GROUP || chatType == ChatType.CHANNEL) {
                        getGroupMembers(chatId)
                    } else {
                        emptyList()
                    }
                val profile =
                    ChatProfile(
                        chatId = chatId,
                        title = title,
                        chatType = chatType,
                        avatarPath = avatarPath,
                        avatarLetters = avatarLetters,
                        statusText = statusText,
                        username = username,
                        phoneNumber = phone,
                        bio = bio,
                        memberCount = if (memberCount > 0) memberCount else membersList.size,
                        isMuted = isMuted,
                        isPremium = isPremium,
                        isForum = chatModel?.isForum ?: false,
                        isSecretChat = chatModel?.isSecretChat ?: false,
                        selfDestructTimerSec = chatModel?.selfDestructTimerSec ?: 0,
                        members = membersList,
                    )

                try {
                    withContext(Dispatchers.Main) {
                        onResult(profile)
                    }
                } catch (e: Throwable) {
                    onResult(profile)
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to load chat profile: ${e.message}")
                val fallbackType = chatModel?.chatType ?: ChatType.DIRECT
                val fallbackMembers =
                    if (fallbackType == ChatType.GROUP || fallbackType == ChatType.CHANNEL) {
                        getGroupMembers(chatId)
                    } else {
                        emptyList()
                    }
                val fallback =
                    ChatProfile(
                        chatId = chatId,
                        title = chatModel?.title ?: "Chat",
                        chatType = fallbackType,
                        avatarPath = chatModel?.avatarPath,
                        avatarLetters = chatModel?.avatarLetters ?: "TG",
                        statusText = "Details unavailable",
                        isPremium = chatModel?.isPremium ?: false,
                        isForum = chatModel?.isForum ?: false,
                        isSecretChat = chatModel?.isSecretChat ?: false,
                        selfDestructTimerSec = chatModel?.selfDestructTimerSec ?: 0,
                        members = fallbackMembers,
                    )
                try {
                    withContext(Dispatchers.Main) {
                        onResult(fallback)
                    }
                } catch (e: Throwable) {
                    onResult(fallback)
                }
            }
        }
    }

    fun toggleChatMute(
        chatId: Long,
        currentMuted: Boolean,
        onResult: ((Boolean) -> Unit)? = null,
    ) {
        val client = tdClient
        if (client == null) {
            onResult?.invoke(currentMuted)
            return
        }
        val newMuted = !currentMuted
        val muteFor = if (newMuted) Int.MAX_VALUE else 0
        setChatNotificationMute(chatId, muteFor, onResult)
    }

    fun getPersistedMutedChatIds(): MutableSet<String> {
        val raw =
            try {
                prefs.getStringSet(prefMutedChatsKey, null)
            } catch (_: Exception) {
                null
            } ?: emptySet()
        return raw.toMutableSet()
    }

    fun persistChatMuted(
        chatId: Long,
        isMuted: Boolean,
    ) {
        val currentSet = getPersistedMutedChatIds()
        val idStr = chatId.toString()
        val changed =
            if (isMuted) {
                currentSet.add(idStr)
            } else {
                currentSet.remove(idStr)
            }
        if (changed) {
            prefs.edit()?.putStringSet(prefMutedChatsKey, HashSet(currentSet))?.apply()
        }
    }

    fun isNotificationSettingsMuted(
        settings: TdApi.ChatNotificationSettings?,
        chatType: ChatType = ChatType.DIRECT,
    ): Boolean {
        if (settings == null) return false
        return if (!settings.useDefaultMuteFor) {
            settings.muteFor > 0
        } else {
            when (chatType) {
                ChatType.DIRECT, ChatType.BOT -> scopePrivateChatsMuted
                ChatType.GROUP -> scopeGroupChatsMuted
                ChatType.CHANNEL -> scopeChannelChatsMuted
            }
        }
    }

    fun isChatMuted(chatId: Long): Boolean {
        val chat = chatMap[chatId]
        if (chat != null) {
            return chat.isMuted
        }
        val settings = chatNotificationSettingsCache[chatId]
        if (settings != null) {
            return isNotificationSettingsMuted(settings, ChatType.DIRECT)
        }
        return getPersistedMutedChatIds().contains(chatId.toString())
    }

    fun handleChatNotificationSettingsUpdated(
        chatId: Long,
        notificationSettings: TdApi.ChatNotificationSettings,
    ) {
        chatNotificationSettingsCache[chatId] = notificationSettings
        val existingChat = chatMap[chatId]
        val chatType = existingChat?.chatType ?: ChatType.DIRECT
        val isMuted = isNotificationSettingsMuted(notificationSettings, chatType)
        persistChatMuted(chatId, isMuted)
        if (isMuted) {
            NotificationHelper.cancelMessageNotification(context, chatId)
        }
        synchronized(chatMap) {
            val existing = chatMap[chatId]
            if (existing != null) {
                chatMap[chatId] = existing.copy(isMuted = isMuted)
            }
        }
        _chats.value = synchronized(chatMap) { chatMap.values.toList() }
    }

    fun handleScopeNotificationSettingsUpdated(
        scope: TdApi.NotificationSettingsScope,
        notificationSettings: TdApi.ScopeNotificationSettings,
    ) {
        val isScopeMuted = notificationSettings.muteFor > 0
        when (scope.constructor) {
            TdApi.NotificationSettingsScopePrivateChats.CONSTRUCTOR -> {
                scopePrivateChatsMuted = isScopeMuted
            }
            TdApi.NotificationSettingsScopeGroupChats.CONSTRUCTOR -> {
                scopeGroupChatsMuted = isScopeMuted
            }
            TdApi.NotificationSettingsScopeChannelChats.CONSTRUCTOR -> {
                scopeChannelChatsMuted = isScopeMuted
            }
        }
        synchronized(chatMap) {
            for ((key, chat) in chatMap) {
                val cached = chatNotificationSettingsCache[chat.id]
                if (cached != null && cached.useDefaultMuteFor) {
                    val newMuted = isNotificationSettingsMuted(cached, chat.chatType)
                    persistChatMuted(chat.id, newMuted)
                    if (newMuted) {
                        NotificationHelper.cancelMessageNotification(context, chat.id)
                    }
                    if (chat.isMuted != newMuted) {
                        chatMap[key] = chat.copy(isMuted = newMuted)
                    }
                }
            }
        }
        _chats.value = synchronized(chatMap) { chatMap.values.toList() }
    }

    fun loadScopeNotificationSettings() {
        val client = tdClient ?: return
        client.send(TdApi.GetScopeNotificationSettings(TdApi.NotificationSettingsScopePrivateChats())) { obj ->
            if (obj is TdApi.ScopeNotificationSettings) {
                handleScopeNotificationSettingsUpdated(TdApi.NotificationSettingsScopePrivateChats(), obj)
            }
        }
        client.send(TdApi.GetScopeNotificationSettings(TdApi.NotificationSettingsScopeGroupChats())) { obj ->
            if (obj is TdApi.ScopeNotificationSettings) {
                handleScopeNotificationSettingsUpdated(TdApi.NotificationSettingsScopeGroupChats(), obj)
            }
        }
        client.send(TdApi.GetScopeNotificationSettings(TdApi.NotificationSettingsScopeChannelChats())) { obj ->
            if (obj is TdApi.ScopeNotificationSettings) {
                handleScopeNotificationSettingsUpdated(TdApi.NotificationSettingsScopeChannelChats(), obj)
            }
        }
    }

    fun setNetworkType(type: TdApi.NetworkType) {
        tdClient?.send(TdApi.SetNetworkType(type)) { /* no-op */ }
    }

    fun setChatNotificationMute(
        chatId: Long,
        muteForSeconds: Int,
        onResult: ((Boolean) -> Unit)? = null,
    ) {
        val isMuted = muteForSeconds > 0
        persistChatMuted(chatId, isMuted)
        if (isMuted) {
            NotificationHelper.cancelMessageNotification(context, chatId)
        }
        synchronized(chatMap) {
            val existing = chatMap[chatId]
            if (existing != null) {
                chatMap[chatId] = existing.copy(isMuted = isMuted)
            }
        }
        _chats.value = synchronized(chatMap) { chatMap.values.toList() }
        val client = tdClient
        if (client == null) {
            onResult?.invoke(isMuted)
            return
        }
        val currentSettings = chatNotificationSettingsCache[chatId]
        val settings =
            TdApi.ChatNotificationSettings().apply {
                if (currentSettings != null) {
                    this.useDefaultSound = currentSettings.useDefaultSound
                    this.soundId = currentSettings.soundId
                    this.useDefaultShowPreview = currentSettings.useDefaultShowPreview
                    this.showPreview = currentSettings.showPreview
                    this.useDefaultMuteStories = currentSettings.useDefaultMuteStories
                    this.muteStories = currentSettings.muteStories
                    this.useDefaultDisablePinnedMessageNotifications = currentSettings.useDefaultDisablePinnedMessageNotifications
                    this.disablePinnedMessageNotifications = currentSettings.disablePinnedMessageNotifications
                    this.useDefaultDisableMentionNotifications = currentSettings.useDefaultDisableMentionNotifications
                    this.disableMentionNotifications = currentSettings.disableMentionNotifications
                } else {
                    this.useDefaultSound = true
                    this.useDefaultShowPreview = true
                    this.useDefaultMuteStories = true
                    this.useDefaultDisablePinnedMessageNotifications = true
                    this.useDefaultDisableMentionNotifications = true
                }
                this.useDefaultMuteFor = false
                this.muteFor = muteForSeconds
            }
        chatNotificationSettingsCache[chatId] = settings
        val req = TdApi.SetChatNotificationSettings(chatId, settings)
        client.send(req) { obj ->
            val success = obj is TdApi.Ok
            if (!success) {
                val revertedMuted = !isMuted
                persistChatMuted(chatId, revertedMuted)
                scope.launch(Dispatchers.Main) {
                    val existing = chatMap[chatId]
                    if (existing != null) {
                        chatMap[chatId] = existing.copy(isMuted = revertedMuted)
                        _chats.value = synchronized(chatMap) { chatMap.values.toList() }
                    }
                    onResult?.invoke(revertedMuted)
                }
            } else {
                scope.launch(Dispatchers.Main) {
                    onResult?.invoke(isMuted)
                }
            }
        }
    }

    // =========================================================================
    // Blocked Users Management
    // =========================================================================
    fun loadBlockedUsers(onResult: (List<ContactModel>) -> Unit) {
        val client = tdClient
        if (client == null) {
            onResult(emptyList())
            return
        }
        val req = TdApi.GetBlockedMessageSenders(TdApi.BlockListMain(), 0, 100)
        client.send(req) { obj ->
            if (obj is TdApi.MessageSenders) {
                val userIds =
                    obj.senders.mapNotNull { sender ->
                        if (sender is TdApi.MessageSenderUser) sender.userId else null
                    }
                if (userIds.isEmpty()) {
                    scope.launch(Dispatchers.Main) { onResult(emptyList()) }
                    return@send
                }
                val blockedList = mutableListOf<ContactModel>()
                val latch = java.util.concurrent.atomic.AtomicInteger(userIds.size)
                for (uid in userIds) {
                    client.send(TdApi.GetUser(uid)) { uObj ->
                        if (uObj is TdApi.User) {
                            blockedList.add(
                                ContactModel(
                                    userId = uObj.id,
                                    firstName = uObj.firstName,
                                    lastName = uObj.lastName,
                                    phoneNumber = uObj.phoneNumber,
                                    username = uObj.usernames?.activeUsernames?.firstOrNull() ?: "",
                                    isOnline = false,
                                ),
                            )
                        }
                        if (latch.decrementAndGet() == 0) {
                            scope.launch(Dispatchers.Main) { onResult(blockedList) }
                        }
                    }
                }
            } else {
                scope.launch(Dispatchers.Main) { onResult(emptyList()) }
            }
        }
    }

    fun blockUser(
        userId: Long,
        onResult: ((Boolean, String?) -> Unit)? = null,
    ) {
        val client = tdClient
        if (client == null) {
            onResult?.invoke(false, "Not connected")
            return
        }
        val req = TdApi.SetMessageSenderBlockList(TdApi.MessageSenderUser(userId), TdApi.BlockListMain())
        client.send(req) { obj ->
            scope.launch(Dispatchers.Main) {
                if (obj is TdApi.Ok) {
                    onResult?.invoke(true, null)
                } else if (obj is TdApi.Error) {
                    onResult?.invoke(false, obj.message)
                } else {
                    onResult?.invoke(false, "Failed to block user")
                }
            }
        }
    }

    fun unblockUser(
        userId: Long,
        onResult: ((Boolean, String?) -> Unit)? = null,
    ) {
        val client = tdClient
        if (client == null) {
            onResult?.invoke(false, "Not connected")
            return
        }
        val req = TdApi.SetMessageSenderBlockList(TdApi.MessageSenderUser(userId), null)
        client.send(req) { obj ->
            scope.launch(Dispatchers.Main) {
                if (obj is TdApi.Ok) {
                    onResult?.invoke(true, null)
                } else if (obj is TdApi.Error) {
                    onResult?.invoke(false, obj.message)
                } else {
                    onResult?.invoke(false, "Failed to unblock user")
                }
            }
        }
    }

    fun logOut() {
        synchronized(clientLock) {
            reconnectPending = false
        }
        tdClient?.send(TdApi.LogOut(), this)
    }

    fun close() {
        val client: Client?
        synchronized(clientLock) {
            reconnectPending = false
            client = tdClient
            if (client == null || isClosingClient) return
            isClosingClient = true
        }
        scope.launch(Dispatchers.IO) {
            try {
                com.teleflip.app.report.AdbReporter.reportInfo(tag, "Closing TDLib client gracefully...")
                client?.send(TdApi.Close(), this@TelegramManager)
            } catch (e: Throwable) {
                com.teleflip.app.report.AdbReporter.reportWarning(tag, "Error during native close: ${e.message}")
            }
        }
    }

    override fun onResult(obj: TdApi.Object?) {
        if (obj == null) return

        when (obj.constructor) {
            TdApi.UpdateCall.CONSTRUCTOR -> {
                val updateCall = obj as TdApi.UpdateCall
                handleCallUpdate(updateCall.call)
            }
            TdApi.UpdateUser.CONSTRUCTOR -> {
                val updateUser = obj as TdApi.UpdateUser
                updateUserCache(updateUser.user)
            }
            TdApi.User.CONSTRUCTOR -> {
                val user = obj as TdApi.User
                updateUserCache(user)
            }
            TdApi.UpdateAuthorizationState.CONSTRUCTOR -> {
                val auth = (obj as TdApi.UpdateAuthorizationState).authorizationState
                handleAuthState(auth)
            }
            TdApi.UpdateConnectionState.CONSTRUCTOR -> {
                val conn = (obj as TdApi.UpdateConnectionState).state
                handleConnectionState(conn)
            }
            TdApi.UpdateNewChat.CONSTRUCTOR -> {
                val updateChat = (obj as TdApi.UpdateNewChat).chat
                addOrUpdateChat(updateChat)
            }
            TdApi.UpdateChatLastMessage.CONSTRUCTOR -> {
                val updateLast = obj as TdApi.UpdateChatLastMessage
                updateChatLastMessage(updateLast.chatId, updateLast.lastMessage)
            }
            TdApi.UpdateChatDraftMessage.CONSTRUCTOR -> {
                val updateDraft = obj as TdApi.UpdateChatDraftMessage
                val draftText = (updateDraft.draftMessage?.content as? TdApi.DraftMessageContentText)?.text?.text.orEmpty()
                handleChatDraftUpdated(updateDraft.chatId, draftText)
            }
            TdApi.UpdateChatNotificationSettings.CONSTRUCTOR -> {
                val update = obj as TdApi.UpdateChatNotificationSettings
                handleChatNotificationSettingsUpdated(update.chatId, update.notificationSettings)
            }
            TdApi.UpdateScopeNotificationSettings.CONSTRUCTOR -> {
                val update = obj as TdApi.UpdateScopeNotificationSettings
                handleScopeNotificationSettingsUpdated(update.scope, update.notificationSettings)
            }
            TdApi.UpdateNewMessage.CONSTRUCTOR -> {
                val updateMsg = obj as TdApi.UpdateNewMessage
                handleIncomingMessage(updateMsg.message)
            }
            TdApi.UpdateMessageContent.CONSTRUCTOR -> {
                val updateContent = obj as TdApi.UpdateMessageContent
                handleMessageContentUpdated(updateContent.chatId, updateContent.messageId, updateContent.newContent)
            }
            TdApi.UpdateMessageEdited.CONSTRUCTOR -> {
                val updateEdited = obj as TdApi.UpdateMessageEdited
                handleMessageEditedFlag(updateEdited.chatId, updateEdited.messageId, updateEdited.editDate)
            }
            TdApi.UpdateMessageSendSucceeded.CONSTRUCTOR -> {
                val update = obj as TdApi.UpdateMessageSendSucceeded
                updateMessageStatus(update.message.chatId, update.oldMessageId, MessageStatus.SENT)
                updateMessageStatus(update.message.chatId, update.message.id, MessageStatus.SENT)
            }
            TdApi.UpdateMessageSendFailed.CONSTRUCTOR -> {
                val update = obj as TdApi.UpdateMessageSendFailed
                updateMessageStatus(update.message.chatId, update.oldMessageId, MessageStatus.FAILED)
                updateMessageStatus(update.message.chatId, update.message.id, MessageStatus.FAILED)
            }
            TdApi.Chats.CONSTRUCTOR -> {
                val chatIds = (obj as TdApi.Chats).chatIds
                dispatchChatBatches(chatIds)
            }
            TdApi.Chat.CONSTRUCTOR -> {
                addOrUpdateChat(obj as TdApi.Chat)
            }
            TdApi.UpdateChatFolders.CONSTRUCTOR -> {
                val updateFolders = obj as TdApi.UpdateChatFolders
                handleUpdateChatFolders(updateFolders.chatFolders)
            }
            TdApi.ChatFolder.CONSTRUCTOR -> {
                val folder = obj as TdApi.ChatFolder
                handleChatFolderReceived(folder)
            }
            TdApi.Messages.CONSTRUCTOR -> {
                val msgs = (obj as TdApi.Messages).messages
                handleMessagesList(msgs)
            }
            TdApi.UpdateFile.CONSTRUCTOR -> {
                val fileUpdate = obj as TdApi.UpdateFile
                handleFileUpdate(fileUpdate.file)
            }
            TdApi.File.CONSTRUCTOR -> {
                val file = obj as TdApi.File
                handleFileUpdate(file)
            }
            TdApi.EmailAddressAuthenticationCodeInfo.CONSTRUCTOR -> {
                isAuthQueryInProgress = false
                val info = obj as TdApi.EmailAddressAuthenticationCodeInfo
                _authState.value = AuthState.WaitPasswordRecoveryCode(recoveryEmailPattern = info.emailAddressPattern ?: "")
            }
            TdApi.Ok.CONSTRUCTOR -> {
                clearAuthTimeoutSafeguard()
                isAuthQueryInProgress = false
            }
            TdApi.Error.CONSTRUCTOR -> {
                clearAuthTimeoutSafeguard()
                isAuthQueryInProgress = false
                val err = obj as TdApi.Error
                com.teleflip.app.report.AdbReporter.reportError(tag, "TDLib Server Error [Code: ${err.code}]: ${err.message}")
                val msg = err.message ?: ""
                if (msg.contains("lock file", ignoreCase = true) || msg.contains("already in use", ignoreCase = true)) {
                    com.teleflip.app.report.AdbReporter.reportWarning(tag, "Database lock contention detected. Attempting clean retry after release...")
                    scope.launch(Dispatchers.IO) {
                        delay(1500L)
                        retryConnection()
                    }
                    return
                }
                when (val curr = _authState.value) {
                    is AuthState.WaitPhoneNumber -> _authState.value = curr.copy(errorMessage = err.message)
                    is AuthState.WaitOtherDeviceConfirmation -> _authState.value = curr.copy(errorMessage = err.message)
                    is AuthState.WaitCode -> _authState.value = curr.copy(errorMessage = err.message)
                    is AuthState.WaitPassword -> _authState.value = curr.copy(errorMessage = err.message)
                    is AuthState.WaitPasswordRecoveryCode -> _authState.value = curr.copy(errorMessage = err.message)
                    else -> {
                        if (_authState.value !is AuthState.Ready && _authState.value !is AuthState.Initial && _authState.value !is AuthState.WaitParameters) {
                            _authState.value = AuthState.WaitPhoneNumber(errorMessage = err.message)
                        }
                    }
                }
            }
        }
    }

    private fun handleAuthState(state: TdApi.AuthorizationState) {
        clearAuthTimeoutSafeguard()
        isAuthQueryInProgress = false
        com.teleflip.app.report.AdbReporter.reportInfo(tag, "Auth state updated: ${state.javaClass.simpleName}")
        when (state.constructor) {
            TdApi.AuthorizationStateWaitTdlibParameters.CONSTRUCTOR -> {
                if (this@TelegramManager.apiId == 0 || this@TelegramManager.apiHash.isBlank()) {
                    _authState.value = AuthState.WaitPhoneNumber(errorMessage = "Please configure your Telegram API ID & Hash in Settings (Config)")
                    return
                }
                val targetDbDir = File(context.filesDir, "tdlib_${this@TelegramManager.apiId}")
                val legacyDbDir = File(context.filesDir, "tdlib")
                if (!targetDbDir.exists() && legacyDbDir.exists() && legacyDbDir.isDirectory) {
                    legacyDbDir.renameTo(targetDbDir)
                }
                if (!targetDbDir.exists()) {
                    targetDbDir.mkdirs()
                }
                val dbDir = targetDbDir.absolutePath
                val filesDir = com.teleflip.app.storage.StorageManager.getFilesDirectory(context).absolutePath
                val params =
                    TdApi.SetTdlibParameters().apply {
                        databaseDirectory = dbDir
                        filesDirectory = filesDir
                        useFileDatabase = true
                        useMessageDatabase = true
                        useSecretChats = true
                        apiId = this@TelegramManager.apiId
                        apiHash = this@TelegramManager.apiHash
                        systemLanguageCode = "en"
                        deviceModel = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim().ifEmpty { "Schok SC3218" }
                        systemVersion = "Android ${android.os.Build.VERSION.RELEASE}"
                        applicationVersion = org.telegram.messenger.BuildVars.BUILD_VERSION_STRING
                    }
                tdClient?.send(params, this)
            }
            TdApi.AuthorizationStateWaitPhoneNumber.CONSTRUCTOR -> {
                _authState.value = AuthState.WaitPhoneNumber()
            }
            TdApi.AuthorizationStateWaitOtherDeviceConfirmation.CONSTRUCTOR -> {
                val waitOther = state as TdApi.AuthorizationStateWaitOtherDeviceConfirmation
                _authState.value = AuthState.WaitOtherDeviceConfirmation(link = waitOther.link ?: "")
            }
            TdApi.AuthorizationStateWaitCode.CONSTRUCTOR -> {
                _authState.value = AuthState.WaitCode()
            }
            TdApi.AuthorizationStateWaitPassword.CONSTRUCTOR -> {
                val waitPass = state as TdApi.AuthorizationStateWaitPassword
                val hint = waitPass.passwordHint ?: ""
                val hasEmail = waitPass.hasRecoveryEmailAddress
                val pattern = waitPass.recoveryEmailAddressPattern ?: ""
                _authState.value =
                    AuthState.WaitPassword(
                        hint = hint,
                        hasRecoveryEmailAddress = hasEmail,
                        recoveryEmailAddressPattern = pattern,
                    )
            }
            TdApi.AuthorizationStateReady.CONSTRUCTOR -> {
                _authState.value = AuthState.Ready
                loadChats(100)
                loadCurrentUser()
                loadChatFolders()
                loadScopeNotificationSettings()
            }
            TdApi.AuthorizationStateClosing.CONSTRUCTOR -> {
                com.teleflip.app.report.AdbReporter.reportInfo(tag, "TDLib is closing gracefully...")
            }
            TdApi.AuthorizationStateLoggingOut.CONSTRUCTOR -> {
                com.teleflip.app.report.AdbReporter.reportInfo(tag, "TDLib is logging out...")
                _authState.value = AuthState.Initial
            }
            TdApi.AuthorizationStateWaitRegistration.CONSTRUCTOR -> {
                com.teleflip.app.report.AdbReporter.reportWarning(tag, "TDLib requires registration (new user)")
                _authState.value = AuthState.WaitPhoneNumber(errorMessage = "Registration required - please sign up with official Telegram first.")
            }
            TdApi.AuthorizationStateWaitEmailAddress.CONSTRUCTOR -> {
                com.teleflip.app.report.AdbReporter.reportWarning(tag, "TDLib requires email address")
                _authState.value = AuthState.WaitPhoneNumber(errorMessage = "Email verification required. Please log in on another device first.")
            }
            TdApi.AuthorizationStateWaitEmailCode.CONSTRUCTOR -> {
                com.teleflip.app.report.AdbReporter.reportWarning(tag, "TDLib requires email code")
                _authState.value = AuthState.WaitPhoneNumber(errorMessage = "Email verification code required.")
            }
            TdApi.AuthorizationStateWaitPremiumPurchase.CONSTRUCTOR -> {
                com.teleflip.app.report.AdbReporter.reportWarning(tag, "TDLib requires premium purchase")
                _authState.value = AuthState.WaitPhoneNumber(errorMessage = "Premium subscription required.")
            }
            TdApi.AuthorizationStateClosed.CONSTRUCTOR -> {
                com.teleflip.app.report.AdbReporter.reportInfo(tag, "AuthorizationStateClosed received. Previous client released.")
                val shouldRestart: Boolean
                synchronized(clientLock) {
                    tdClient = null
                    isClosingClient = false
                    shouldRestart = reconnectPending
                    reconnectPending = false
                }
                _authState.value = AuthState.Initial
                if (shouldRestart) {
                    start()
                }
            }
        }
    }

    private fun handleConnectionState(state: TdApi.ConnectionState) {
        val isProxyActive = getEnabledProxy() != null || activeProxyId != 0
        val label =
            when (state.constructor) {
                TdApi.ConnectionStateConnecting.CONSTRUCTOR -> if (isProxyActive) "Connecting via Proxy..." else "Connecting..."
                TdApi.ConnectionStateConnectingToProxy.CONSTRUCTOR -> "Proxy..."
                TdApi.ConnectionStateUpdating.CONSTRUCTOR -> "Updating..."
                TdApi.ConnectionStateReady.CONSTRUCTOR -> if (isProxyActive) "Online via Proxy" else "Online"
                else -> "Waiting for network"
            }
        _connectionState.value = label
        com.teleflip.app.report.AdbReporter.reportInfo(tag, "Connection state: $label")
    }

    fun updateUserCache(user: TdApi.User) {
        userCache[user.id] = user
        userSubscriberCache[user.id] = user.isPremium
        subscriberCache[user.id] = user.isPremium
        if (!user.phoneNumber.isNullOrBlank()) {
            userPhoneCache[user.id] = user.phoneNumber
        }
        var updated = false
        synchronized(chatMap) {
            for ((id, chat) in chatMap) {
                val uId = chatIdToUserId[id] ?: if (chat.chatType == ChatType.DIRECT) id else null
                if (uId == user.id && chat.isPremium != user.isPremium) {
                    chatMap[id] = chat.copy(isPremium = user.isPremium)
                    updated = true
                }
            }
        }
        if (updated) {
            _chats.value = synchronized(chatMap) { chatMap.values.toList() }
        }
        synchronized(pendingChats) {
            for ((id, chat) in pendingChats) {
                val uId = chatIdToUserId[id] ?: if (chat.chatType == ChatType.DIRECT) id else null
                if (uId == user.id && chat.isPremium != user.isPremium) {
                    pendingChats[id] = chat.copy(isPremium = user.isPremium)
                }
            }
        }
    }

    private val pendingChats = mutableMapOf<Long, ChatModel>()
    private var batchJob: Job? = null

    fun handleUserUpdate(user: TdApi.User) {
        userCache[user.id] = user
        subscriberCache[user.id] = user.isPremium
        if (!user.phoneNumber.isNullOrBlank()) {
            userPhoneCache[user.id] = user.phoneNumber
        }

        var updated = false
        synchronized(chatMap) {
            val chat = chatMap[user.id]
            if (chat != null && chat.chatType == ChatType.DIRECT && chat.isPremium != user.isPremium) {
                chatMap[user.id] = chat.copy(isPremium = user.isPremium)
                updated = true
            }
        }
        if (updated) {
            _chats.value = synchronized(chatMap) { chatMap.values.toList() }
        }

        synchronized(pendingChats) {
            val pendingModel = pendingChats[user.id]
            if (pendingModel != null) {
                pendingChats[user.id] = pendingModel.copy(isPremium = user.isPremium)
            }
        }
    }

    private fun addOrUpdateChat(chat: TdApi.Chat) {
        val title = chat.title.ifBlank { "Chat #${chat.id}" }
        val lastMsg = chat.lastMessage
        val lastMsgText = extractMessageText(lastMsg?.content)
        val timeStr = if (lastMsg != null) formatTime(lastMsg.date.toLong() * 1000) else ""

        val type =
            when (chat.type.constructor) {
                TdApi.ChatTypePrivate.CONSTRUCTOR -> ChatType.DIRECT
                TdApi.ChatTypeBasicGroup.CONSTRUCTOR, TdApi.ChatTypeSupergroup.CONSTRUCTOR -> ChatType.GROUP
                else -> ChatType.CHANNEL
            }

        var isPremium = false
        if (chat.type.constructor == TdApi.ChatTypePrivate.CONSTRUCTOR) {
            val privateUserId = (chat.type as TdApi.ChatTypePrivate).userId
            chatIdToUserId[chat.id] = privateUserId
            val cachedPremium = subscriberCache[privateUserId]
            if (cachedPremium != null) {
                isPremium = cachedPremium
            } else {
                tdClient?.send(TdApi.GetUser(privateUserId), this)
            }
        }

        val letters =
            title.split(" ")
                .filter { it.isNotBlank() }
                .take(2)
                .map { it.first().uppercaseChar() }
                .joinToString("")
                .ifEmpty { "TG" }

        val photo = chat.photo
        val small = photo?.small
        var avatarPath: String? = null
        var avatarFileId = 0
        if (small != null) {
            avatarFileId = small.id
            if (small.local.isDownloadingCompleted && small.local.path.isNotBlank()) {
                avatarPath = small.local.path
            } else if (small.local.canBeDownloaded && !small.local.isDownloadingActive) {
                tdClient?.send(TdApi.DownloadFile(small.id, 32, 0, 0, false), this)
            }
        }

        val existingModel = chatMap[chat.id]
        val isPinnedChat = existingModel?.isPinned ?: false
        chat.notificationSettings?.let {
            chatNotificationSettingsCache[chat.id] = it
        }
        val isMutedChat =
            chat.notificationSettings?.let {
                isNotificationSettingsMuted(it, type)
            } ?: (existingModel?.isMuted ?: getPersistedMutedChatIds().contains(chat.id.toString()))
        persistChatMuted(chat.id, isMutedChat)
        val cloudDraft = (chat.draftMessage?.content as? TdApi.DraftMessageContentText)?.text?.text.orEmpty()
        if (cloudDraft.isNotEmpty() && !chatDrafts.containsKey(chat.id)) {
            chatDrafts[chat.id] = cloudDraft
        }
        val draftStr = chatDrafts[chat.id] ?: cloudDraft

        val newModel =
            ChatModel(
                id = chat.id,
                title = title,
                lastMessage = lastMsgText,
                time = timeStr,
                unreadCount = chat.unreadCount,
                chatType = type,
                avatarLetters = letters,
                isPinned = isPinnedChat,
                avatarPath = avatarPath,
                avatarFileId = avatarFileId,
                isPremium = isPremium,
                isMuted = isMutedChat,
                draftText = draftStr,
                pinnedMessageId = existingModel?.pinnedMessageId ?: 0L,
            )

        synchronized(pendingChats) {
            pendingChats[chat.id] = newModel
        }

        if (batchJob?.isActive != true) {
            batchJob =
                scope.launch {
                    while (true) {
                        delay(120)
                        val updates: List<ChatModel>
                        synchronized(pendingChats) {
                            if (pendingChats.isEmpty()) return@launch
                            updates = pendingChats.values.toList()
                            pendingChats.clear()
                        }
                        synchronized(chatMap) {
                            for (model in updates) {
                                chatMap[model.id] = model
                            }
                        }
                        _chats.value = synchronized(chatMap) { chatMap.values.toList() }
                    }
                }
        }
    }

    private fun updateChatLastMessage(
        chatId: Long,
        msg: TdApi.Message?,
    ) {
        var updated = false
        synchronized(chatMap) {
            val old = chatMap[chatId]
            if (old != null) {
                val lastMsgText = extractMessageText(msg?.content)
                val timeStr = if (msg != null) formatTime(msg.date.toLong() * 1000) else old.time
                chatMap[chatId] = old.copy(lastMessage = lastMsgText, time = timeStr)
                updated = true
            }
        }
        if (updated) {
            _chats.value = synchronized(chatMap) { chatMap.values.toList() }
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun extractStickerInfo(content: TdApi.MessageContent?): Pair<String, Boolean> {
        if (content == null) return Pair("", false)
        if (content.constructor == TdApi.MessageSticker.CONSTRUCTOR) {
            val sticker = (content as TdApi.MessageSticker).sticker
            if (sticker != null) {
                val emoji = sticker.emoji ?: ""
                val isPremium = sticker.fullType?.javaClass?.simpleName?.contains("Premium") == true
                return Pair(emoji, isPremium)
            }
        }
        return Pair("", false)
    }

    private fun extractVoiceTranscript(content: TdApi.MessageContent?): String {
        if (content is TdApi.MessageVoiceNote) {
            val result = content.voiceNote?.speechRecognitionResult ?: return ""
            return when (result.constructor) {
                TdApi.SpeechRecognitionResultText.CONSTRUCTOR -> {
                    (result as TdApi.SpeechRecognitionResultText).text ?: ""
                }
                TdApi.SpeechRecognitionResultPending.CONSTRUCTOR -> {
                    (result as TdApi.SpeechRecognitionResultPending).partialText ?: ""
                }
                else -> ""
            }
        }
        return ""
    }

    private fun handleMessageContentUpdated(
        chatId: Long,
        messageId: Long,
        newContent: TdApi.MessageContent?,
    ) {
        val newText = extractMessageText(newContent)
        val newCaption = extractMediaCaption(newContent)
        val newMediaType = extractMediaType(newContent)
        val newMediaInfo = extractMediaFileInfo(newContent)
        val starCount = extractStarCount(newContent)
        val isLocked = extractIsPaidMediaLocked(newContent)
        val newTranscript = extractVoiceTranscript(newContent)
        val currentMap = _messages.value.toMutableMap()
        val list = (currentMap[chatId] ?: return).toMutableList()
        val idx = list.indexOfFirst { it.id == messageId }
        if (idx >= 0) {
            val old = list[idx]
            list[idx] =
                old.copy(
                    text = if (newText.isNotBlank()) newText else old.text,
                    mediaCaption = newCaption,
                    mediaType = newMediaType,
                    mediaFileId = if (newMediaInfo.first != 0) newMediaInfo.first else old.mediaFileId,
                    mediaFilePath = newMediaInfo.second ?: old.mediaFilePath,
                    starCount = starCount,
                    isPaidMediaLocked = isLocked,
                    transcript = if (newTranscript.isNotBlank()) newTranscript else old.transcript,
                    isEdited = true,
                )
            currentMap[chatId] = list
            _messages.value = currentMap
        }
    }

    private fun handleMessageEditedFlag(
        chatId: Long,
        messageId: Long,
        editDate: Int,
    ) {
        if (editDate <= 0) return
        val currentMap = _messages.value.toMutableMap()
        val list = (currentMap[chatId] ?: return).toMutableList()
        val idx = list.indexOfFirst { it.id == messageId }
        if (idx >= 0) {
            list[idx] = list[idx].copy(isEdited = true)
            currentMap[chatId] = list
            _messages.value = currentMap
        }
    }

    private fun handleIncomingMessage(msg: TdApi.Message) {
        val mediaType = extractMediaType(msg.content)
        val mediaInfo = extractMediaFileInfo(msg.content)
        val stickerInfo = extractStickerInfo(msg.content)
        val model =
            MessageModel(
                id = msg.id,
                chatId = msg.chatId,
                senderName = "User",
                text = extractMessageText(msg.content),
                time = formatTime(msg.date.toLong() * 1000),
                isOutbound = msg.isOutgoing,
                status = if (msg.isOutgoing) MessageStatus.SENT else MessageStatus.READ,
                mediaType = mediaType,
                mediaCaption = extractMediaCaption(msg.content),
                mediaFilePath = mediaInfo.second,
                mediaFileId = mediaInfo.first,
                stickerEmoji = stickerInfo.first,
                isPremiumSticker = stickerInfo.second,
                starCount = extractStarCount(msg.content),
                isPaidMediaLocked = extractIsPaidMediaLocked(msg.content),
                transcript = extractVoiceTranscript(msg.content),
            )

        appendMessage(msg.chatId, model)
        if (!msg.isOutgoing && backgroundPushEnabled) {
            val targetChat = chatMap[msg.chatId]
            val cachedSettings = chatNotificationSettingsCache[msg.chatId]
            if (targetChat == null && cachedSettings == null) {
                val client = tdClient
                if (client != null) {
                    client.send(TdApi.GetChat(msg.chatId)) { obj ->
                        if (obj is TdApi.Chat) {
                            val settings = obj.notificationSettings
                            if (settings != null) {
                                chatNotificationSettingsCache[msg.chatId] = settings
                                val isMuted = isNotificationSettingsMuted(settings, ChatType.DIRECT)
                                persistChatMuted(msg.chatId, isMuted)
                                if (!isMuted) {
                                    NotificationHelper.showIncomingMessageNotification(
                                        context = context,
                                        chatId = msg.chatId,
                                        chatTitle = obj.title.ifBlank { "Telegram" },
                                        senderName = model.senderName,
                                        messageText = model.text,
                                    )
                                }
                            } else if (!isChatMuted(msg.chatId)) {
                                NotificationHelper.showIncomingMessageNotification(
                                    context = context,
                                    chatId = msg.chatId,
                                    chatTitle = obj.title.ifBlank { "Telegram" },
                                    senderName = model.senderName,
                                    messageText = model.text,
                                )
                            }
                        } else {
                            if (!isChatMuted(msg.chatId)) {
                                NotificationHelper.showIncomingMessageNotification(
                                    context = context,
                                    chatId = msg.chatId,
                                    chatTitle = "Telegram",
                                    senderName = model.senderName,
                                    messageText = model.text,
                                )
                            }
                        }
                    }
                    return
                }
            }
            if (!isChatMuted(msg.chatId)) {
                val chatTitle = targetChat?.title ?: "Telegram"
                NotificationHelper.showIncomingMessageNotification(
                    context = context,
                    chatId = msg.chatId,
                    chatTitle = chatTitle,
                    senderName = model.senderName,
                    messageText = model.text,
                )
            }
        }
    }

    private fun handleMessagesList(msgs: Array<TdApi.Message>) {
        if (msgs.isEmpty()) return
        val chatId = msgs.first().chatId
        val list =
            msgs.reversed().map { msg ->
                val mediaInfo = extractMediaFileInfo(msg.content)
                val stickerInfo = extractStickerInfo(msg.content)
                MessageModel(
                    id = msg.id,
                    chatId = msg.chatId,
                    senderName = if (msg.isOutgoing) "You" else "Contact",
                    text = extractMessageText(msg.content),
                    time = formatTime(msg.date.toLong() * 1000),
                    isOutbound = msg.isOutgoing,
                    status = MessageStatus.READ,
                    mediaType = extractMediaType(msg.content),
                    mediaCaption = extractMediaCaption(msg.content),
                    mediaFilePath = mediaInfo.second,
                    mediaFileId = mediaInfo.first,
                    stickerEmoji = stickerInfo.first,
                    isPremiumSticker = stickerInfo.second,
                    starCount = extractStarCount(msg.content),
                    isPaidMediaLocked = extractIsPaidMediaLocked(msg.content),
                    transcript = extractVoiceTranscript(msg.content),
                )
            }
        val currentMap = _messages.value.toMutableMap()
        val existing = currentMap[chatId] ?: emptyList()
        val merged = (list + existing).distinctBy { it.id }.sortedBy { it.id }
        currentMap[chatId] = merged
        _messages.value = currentMap
    }

    private fun handleFileUpdate(file: TdApi.File) {
        if (!file.local.isDownloadingCompleted || file.local.path.isBlank()) return

        fileDownloadWaiters.remove(file.id)?.forEach { deferred ->
            deferred.complete(file.local.path)
        }

        synchronized(pendingChats) {
            for ((id, chat) in pendingChats) {
                if (chat.avatarFileId == file.id) {
                    pendingChats[id] = chat.copy(avatarPath = file.local.path)
                }
            }
        }

        // 1. Check if matches any chat avatar
        var chatUpdated = false
        synchronized(chatMap) {
            for ((id, chat) in chatMap) {
                if (chat.avatarFileId == file.id && chat.avatarPath != file.local.path) {
                    chatMap[id] = chat.copy(avatarPath = file.local.path)
                    chatUpdated = true
                }
            }
        }
        if (chatUpdated) {
            _chats.value = synchronized(chatMap) { chatMap.values.toList() }
        }

        // 2. Check if matches any message media
        val currentMsgs = _messages.value.toMutableMap()
        var msgUpdated = false
        for ((cId, list) in currentMsgs) {
            val updatedList =
                list.map { msg ->
                    if (msg.mediaFileId == file.id && msg.mediaFilePath != file.local.path) {
                        msgUpdated = true
                        msg.copy(mediaFilePath = file.local.path)
                    } else {
                        msg
                    }
                }
            if (msgUpdated) {
                currentMsgs[cId] = updatedList
            }
        }
        if (msgUpdated) {
            _messages.value = currentMsgs
            if (com.teleflip.app.storage.StorageManager.isSaveToDownloadsEnabled(context)) {
                scope.launch(Dispatchers.IO) {
                    try {
                        val src = File(file.local.path)
                        if (src.exists() && src.isFile) {
                            val destDir = com.teleflip.app.storage.StorageManager.getDownloadDirectory(context)
                            val dest = File(destDir, src.name)
                            if (!dest.exists()) {
                                src.copyTo(dest, overwrite = false)
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    private fun extractStarCount(content: TdApi.MessageContent?): Long {
        if (content == null) return 0L
        return if (content.constructor == TdApi.MessagePaidMedia.CONSTRUCTOR) {
            (content as TdApi.MessagePaidMedia).starCount
        } else {
            0L
        }
    }

    private fun extractIsPaidMediaLocked(content: TdApi.MessageContent?): Boolean {
        if (content == null) return false
        if (content.constructor == TdApi.MessagePaidMedia.CONSTRUCTOR) {
            val paidMedia = content as TdApi.MessagePaidMedia
            if (paidMedia.media.isEmpty()) return true
            return paidMedia.media.any { it.constructor == TdApi.PaidMediaPreview.CONSTRUCTOR }
        }
        return false
    }

    fun unlockPaidMedia(
        chatId: Long,
        messageId: Long,
        callback: ((Boolean) -> Unit)? = null,
    ) {
        val client = tdClient
        if (client == null) {
            callback?.invoke(false)
            return
        }
        client.send(
            TdApi.OpenMessageContent(chatId, messageId),
            object : Client.ResultHandler {
                override fun onResult(obj: TdApi.Object?) {
                    val success = obj is TdApi.Ok
                    if (!success && obj is TdApi.Error) {
                        Log.e("TelegramManager", "Failed to unlock paid media: ${obj.message}")
                    }
                    callback?.invoke(success)
                }
            },
        )
    }

    private fun extractMediaCaption(content: TdApi.MessageContent?): String {
        if (content == null) return ""
        return when (content.constructor) {
            TdApi.MessagePhoto.CONSTRUCTOR -> (content as TdApi.MessagePhoto).caption.text
            TdApi.MessageDocument.CONSTRUCTOR -> {
                val doc = content as TdApi.MessageDocument
                doc.caption.text.ifBlank { doc.document.fileName }
            }
            TdApi.MessageVideo.CONSTRUCTOR -> (content as TdApi.MessageVideo).caption.text
            TdApi.MessagePaidMedia.CONSTRUCTOR -> (content as TdApi.MessagePaidMedia).caption.text
            else -> ""
        }
    }

    private fun extractMediaFileInfo(content: TdApi.MessageContent?): Pair<Int, String?> {
        if (content == null) return Pair(0, null)
        return when (content.constructor) {
            TdApi.MessagePhoto.CONSTRUCTOR -> {
                val photo = content as TdApi.MessagePhoto
                val size = photo.photo.sizes.lastOrNull()
                resolveFile(size?.photo)
            }
            TdApi.MessageVoiceNote.CONSTRUCTOR -> {
                val voice = (content as TdApi.MessageVoiceNote).voiceNote.voice
                resolveFile(voice)
            }
            TdApi.MessageAudio.CONSTRUCTOR -> {
                val audio = (content as TdApi.MessageAudio).audio.audio
                resolveFile(audio)
            }
            TdApi.MessageAnimation.CONSTRUCTOR -> {
                val anim = (content as TdApi.MessageAnimation).animation
                resolveFile(anim.animation)
            }
            TdApi.MessageSticker.CONSTRUCTOR -> {
                val sticker = (content as TdApi.MessageSticker).sticker
                resolveFile(sticker?.sticker)
            }
            TdApi.MessageVideo.CONSTRUCTOR -> {
                val video = (content as TdApi.MessageVideo).video
                resolveFile(video.video)
            }
            TdApi.MessageDocument.CONSTRUCTOR -> {
                val doc = (content as TdApi.MessageDocument).document
                resolveFile(doc.document)
            }
            TdApi.MessagePaidMedia.CONSTRUCTOR -> {
                val paidMedia = content as TdApi.MessagePaidMedia
                for (m in paidMedia.media) {
                    when (m.constructor) {
                        TdApi.PaidMediaPhoto.CONSTRUCTOR -> {
                            val photo = (m as TdApi.PaidMediaPhoto).photo
                            val size = photo.sizes.lastOrNull()
                            if (size != null) return resolveFile(size.photo)
                        }
                        TdApi.PaidMediaVideo.CONSTRUCTOR -> {
                            val video = (m as TdApi.PaidMediaVideo).video
                            if (video != null) return resolveFile(video.video)
                        }
                    }
                }
                Pair(0, null)
            }
            else -> Pair(0, null)
        }
    }

    private fun resolveFile(f: TdApi.File?): Pair<Int, String?> {
        if (f == null) return Pair(0, null)
        return if (f.local.isDownloadingCompleted && f.local.path.isNotBlank()) {
            Pair(f.id, f.local.path)
        } else {
            if (f.local.canBeDownloaded && !f.local.isDownloadingActive) {
                if (com.teleflip.app.storage.StorageManager.shouldAutoDownloadMedia(context)) {
                    tdClient?.send(TdApi.DownloadFile(f.id, 1, 0, 0, false), this)
                }
            }
            Pair(f.id, null)
        }
    }

    fun deleteMessage(
        chatId: Long,
        messageId: Long,
    ) {
        tdClient?.send(TdApi.DeleteMessages(chatId, longArrayOf(messageId), true), this)
        val currentMap = _messages.value.toMutableMap()
        val list = currentMap[chatId]?.filter { it.id != messageId } ?: emptyList()
        currentMap[chatId] = list
        _messages.value = currentMap
    }

    private fun extractMediaType(content: TdApi.MessageContent?): MediaType {
        if (content == null) return MediaType.NONE
        return when (content.constructor) {
            TdApi.MessageVoiceNote.CONSTRUCTOR -> MediaType.VOICE
            TdApi.MessageLocation.CONSTRUCTOR -> MediaType.LOCATION
            TdApi.MessagePhoto.CONSTRUCTOR -> MediaType.PHOTO
            TdApi.MessageDocument.CONSTRUCTOR -> MediaType.DOCUMENT
            TdApi.MessageSticker.CONSTRUCTOR -> MediaType.STICKER
            TdApi.MessageVideo.CONSTRUCTOR -> MediaType.VIDEO
            TdApi.MessageAnimation.CONSTRUCTOR -> MediaType.ANIMATION
            TdApi.MessagePoll.CONSTRUCTOR -> MediaType.POLL
            TdApi.MessagePaidMedia.CONSTRUCTOR -> MediaType.PAID_MEDIA
            else -> MediaType.NONE
        }
    }

    private fun extractMessageText(content: TdApi.MessageContent?): String {
        if (content == null) return ""
        return when (content.constructor) {
            TdApi.MessageText.CONSTRUCTOR -> (content as TdApi.MessageText).text.text
            TdApi.MessagePhoto.CONSTRUCTOR -> "📷 [Photo] " + (content as TdApi.MessagePhoto).caption.text
            TdApi.MessageVoiceNote.CONSTRUCTOR -> "🎤 [Voice Note]"
            TdApi.MessageLocation.CONSTRUCTOR -> "📍 [Location]"
            TdApi.MessageAudio.CONSTRUCTOR -> "🎵 [Audio]"
            TdApi.MessageVideo.CONSTRUCTOR -> "📹 [Video] " + (content as TdApi.MessageVideo).caption.text
            TdApi.MessageAnimation.CONSTRUCTOR -> "🎞️ [GIF] " + (content as TdApi.MessageAnimation).caption.text
            TdApi.MessagePoll.CONSTRUCTOR -> "📊 [Poll]"
            TdApi.MessageDocument.CONSTRUCTOR -> "📄 " + (content as TdApi.MessageDocument).document.fileName
            TdApi.MessageSticker.CONSTRUCTOR -> {
                val emoji = (content as TdApi.MessageSticker).sticker?.emoji ?: ""
                if (emoji.isNotBlank()) "🎨 [Sticker $emoji]" else "🎨 [Sticker]"
            }
            TdApi.MessagePaidMedia.CONSTRUCTOR -> {
                val paid = content as TdApi.MessagePaidMedia
                val isLocked = extractIsPaidMediaLocked(content)
                val lockTag = if (isLocked) "🔒 Locked" else "🔓 Unlocked"
                val cap = paid.caption.text
                val base = "⭐ ${paid.starCount} Stars • $lockTag"
                if (cap.isNotBlank()) "$base • $cap" else base
            }
            else -> "[Message]"
        }
    }

    private fun formatTime(millis: Long): String {
        val df = SimpleDateFormat("HH:mm", Locale.getDefault())
        return df.format(Date(millis))
    }

    var activeProxyId: Int = 0

    fun getProxies(): List<ProxyItem> {
        return ProxyStorage.getProxies(context)
    }

    fun getEnabledProxy(): ProxyItem? {
        return ProxyStorage.getEnabledProxy(context)
    }

    private fun buildTdApiProxy(proxyItem: ProxyItem): TdApi.Proxy {
        val proxyType: TdApi.ProxyType =
            when (proxyItem.type) {
                ProxyType.SOCKS5 -> TdApi.ProxyTypeSocks5(proxyItem.username, proxyItem.password)
                ProxyType.MTPROTO -> TdApi.ProxyTypeMtproto(proxyItem.secret)
            }
        return TdApi.Proxy(proxyItem.server, proxyItem.port, proxyType)
    }

    fun applySavedProxy() {
        val enabledProxy = ProxyStorage.getEnabledProxy(context) ?: return
        scope.launch {
            try {
                val tdProxy = buildTdApiProxy(enabledProxy)
                val result = sendQuery(TdApi.AddProxy(tdProxy, true, ""))
                if (result is TdApi.AddedProxy) {
                    activeProxyId = result.id
                    sendQuery(TdApi.EnableProxy(result.id))
                    val updatedItem = enabledProxy.copy(id = result.id, isEnabled = true)
                    ProxyStorage.saveProxy(context, updatedItem)
                    com.teleflip.app.report.AdbReporter.reportInfo(
                        tag,
                        "Applied saved proxy id=${result.id} (${enabledProxy.server}:${enabledProxy.port})",
                    )
                }
            } catch (e: Exception) {
                com.teleflip.app.report.AdbReporter.reportError(tag, "Failed to apply saved proxy: ${e.message}")
            }
        }
    }

    fun addProxy(
        server: String,
        port: Int,
        type: ProxyType,
        username: String = "",
        password: String = "",
        secret: String = "",
        enableNow: Boolean = true,
        callback: ((Boolean, String?) -> Unit)? = null,
    ) {
        val newItem =
            ProxyItem(
                server = server,
                port = port,
                type = type,
                username = username,
                password = password,
                secret = secret,
                isEnabled = enableNow,
            )
        val saved = ProxyStorage.saveProxy(context, newItem)

        if (tdClient == null) {
            callback?.invoke(true, "Proxy saved locally (client offline)")
            return
        }

        scope.launch {
            try {
                val tdProxy = buildTdApiProxy(saved)
                val addResult = sendQuery(TdApi.AddProxy(tdProxy, enableNow, ""))
                if (addResult is TdApi.AddedProxy) {
                    val realId = addResult.id
                    val updatedSaved = saved.copy(id = realId, isEnabled = enableNow)
                    ProxyStorage.saveProxy(context, updatedSaved)

                    if (enableNow) {
                        activeProxyId = realId
                        sendQuery(TdApi.EnableProxy(realId))
                        if (_connectionState.value == "Online" || _connectionState.value == "Connecting...") {
                            _connectionState.value = "Online via Proxy"
                        }
                    }
                    callback?.invoke(true, null)
                } else {
                    callback?.invoke(false, "Unexpected result from AddProxy")
                }
            } catch (e: Exception) {
                com.teleflip.app.report.AdbReporter.reportError(tag, "Error adding proxy: ${e.message}")
                callback?.invoke(false, e.message ?: "Failed to add proxy")
            }
        }
    }

    fun enableProxy(
        proxyId: Int,
        callback: ((Boolean, String?) -> Unit)? = null,
    ) {
        ProxyStorage.enableProxy(context, proxyId)
        val item = ProxyStorage.getProxies(context).find { it.id == proxyId }
        if (item == null) {
            callback?.invoke(false, "Proxy not found")
            return
        }

        if (tdClient == null) {
            activeProxyId = proxyId
            callback?.invoke(true, "Proxy enabled locally")
            return
        }

        scope.launch {
            try {
                val tdProxy = buildTdApiProxy(item)
                val addResult = sendQuery(TdApi.AddProxy(tdProxy, true, ""))
                val realId = if (addResult is TdApi.AddedProxy) addResult.id else proxyId
                val enableResult = sendQuery(TdApi.EnableProxy(realId))
                if (enableResult is TdApi.Ok) {
                    activeProxyId = realId
                    if (_connectionState.value == "Online" || _connectionState.value == "Connecting...") {
                        _connectionState.value = "Online via Proxy"
                    }
                    callback?.invoke(true, null)
                } else {
                    callback?.invoke(false, "Failed to enable proxy in TDLib")
                }
            } catch (e: Exception) {
                com.teleflip.app.report.AdbReporter.reportError(tag, "Error enabling proxy: ${e.message}")
                callback?.invoke(false, e.message ?: "Failed to enable proxy")
            }
        }
    }

    fun disableProxy(callback: ((Boolean, String?) -> Unit)? = null) {
        ProxyStorage.disableAllProxies(context)
        activeProxyId = 0
        if (_connectionState.value == "Online via Proxy") {
            _connectionState.value = "Online"
        }

        if (tdClient == null) {
            callback?.invoke(true, null)
            return
        }

        scope.launch {
            try {
                val result = sendQuery(TdApi.DisableProxy())
                if (result is TdApi.Ok) {
                    callback?.invoke(true, null)
                } else {
                    callback?.invoke(false, "Failed to disable proxy")
                }
            } catch (e: Exception) {
                com.teleflip.app.report.AdbReporter.reportError(tag, "Error disabling proxy: ${e.message}")
                callback?.invoke(false, e.message ?: "Failed to disable proxy")
            }
        }
    }

    fun deleteProxy(
        proxyId: Int,
        callback: ((Boolean, String?) -> Unit)? = null,
    ) {
        val item = ProxyStorage.getProxies(context).find { it.id == proxyId }
        if (item?.isEnabled == true) {
            disableProxy()
        }
        ProxyStorage.deleteProxy(context, proxyId)

        if (tdClient == null) {
            callback?.invoke(true, null)
            return
        }

        scope.launch {
            try {
                val result = sendQuery(TdApi.RemoveProxy(proxyId))
                if (result is TdApi.Ok) {
                    callback?.invoke(true, null)
                } else {
                    callback?.invoke(true, null)
                }
            } catch (e: Exception) {
                callback?.invoke(true, null)
            }
        }
    }
}
