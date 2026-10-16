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
import com.teleflip.app.model.ForumTopicModel
import com.teleflip.app.model.GroupMember
import com.teleflip.app.model.MediaType
import com.teleflip.app.model.MessageModel
import com.teleflip.app.model.MessageSearchResult
import com.teleflip.app.model.MessageStatus
import com.teleflip.app.model.PollOption
import com.teleflip.app.notifications.NotificationHelper
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

sealed class AuthState {
    object Initial : AuthState()

    object WaitParameters : AuthState()

    data class WaitPhoneNumber(val errorMessage: String? = null) : AuthState()

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

class TelegramManager(private val context: Context) : Client.ResultHandler {
    private val tag = "TelegramManager"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs: SharedPreferences = context.getSharedPreferences("teleflip_prefs", Context.MODE_PRIVATE)

    private val _connectionState = MutableStateFlow("Offline")
    val connectionState: StateFlow<String> = _connectionState.asStateFlow()

    private val _authState = MutableStateFlow<AuthState>(AuthState.Initial)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

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

    private val _chatFolders = MutableStateFlow<List<ChatFolderModel>>(emptyList())
    val chatFolders: StateFlow<List<ChatFolderModel>> = _chatFolders.asStateFlow()

    private var tdClient: Client? = null

    @androidx.annotation.VisibleForTesting
    internal fun setTdClientForTesting(client: Client?) {
        this.tdClient = client
    }

    @androidx.annotation.VisibleForTesting
    internal fun setChatsForTesting(chats: List<ChatModel>) {
        _chats.value = chats
    }

    @androidx.annotation.VisibleForTesting
    internal fun setMessagesForTesting(messages: Map<Long, List<MessageModel>>) {
        _messages.value = messages
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

    var chatBatchSize: Int = DEFAULT_CHAT_BATCH_SIZE

    companion object {
        const val DEFAULT_CHAT_BATCH_SIZE = 10
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
        try {
            System.loadLibrary("tdjni")
            Client.execute(TdApi.SetLogVerbosityLevel(1))
            com.teleflip.app.report.AdbReporter.reportInfo(tag, "TDLib tdjni native library loaded successfully.")
        } catch (e: Throwable) {
            com.teleflip.app.report.AdbReporter.reportCrash(tag, e)
        }
    }

    fun start() {
        scope.launch(Dispatchers.IO) {
            try {
                _connectionState.value = "Connecting..."
                com.teleflip.app.report.AdbReporter.reportInfo(tag, "Creating TDLib Client...")
                val client = Client.create(this@TelegramManager, null, null)
                if (client == null) {
                    _connectionState.value = "Init error"
                    com.teleflip.app.report.AdbReporter.reportError(tag, "Client.create returned null")
                } else {
                    tdClient = client
                }
            } catch (e: Throwable) {
                com.teleflip.app.report.AdbReporter.reportError(tag, "Client creation error: ${e.message}", e)
                _connectionState.value = "Init error"
            }
        }
    }

    fun retryConnection() {
        clearAuthTimeoutSafeguard()
        isAuthQueryInProgress = false
        scope.launch(Dispatchers.IO) {
            try {
                com.teleflip.app.report.AdbReporter.reportInfo(tag, "Closing previous TDLib client instance...")
                tdClient?.send(TdApi.Close(), this@TelegramManager)
            } catch (e: Throwable) {
                Log.w(tag, "Error during native client teardown: ${e.message}")
            }
            tdClient = null
            start()
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

    fun sendMessage(
        chatId: Long,
        text: String,
        replyToMessageId: Long = 0L,
        replyToPreview: String = "",
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

        val activeChatModel = _chats.value.find { it.id == chatId }
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
                _chats.value =
                    _chats.value.map {
                        if (it.id == chatId) it.copy(lastMessage = trimmed) else it
                    }
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
            val reactionTypeClass = Class.forName("org.drinkless.tdlib.TdApi\$ReactionTypeEmoji")
            val reactionType = reactionTypeClass.getDeclaredConstructor().newInstance()
            reactionTypeClass.getField("emoji").set(reactionType, emoji)

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
        val updated =
            _chats.value
                .map { if (it.id == chatId) it.copy(isPinned = newPinned) else it }
                .sortedByDescending { it.isPinned }
        _chats.value = updated

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

        _chats.value =
            _chats.value.map { chat ->
                if (chat.id == chatId) {
                    chat.copy(pinnedMessageId = if (newPinned) messageId else 0L)
                } else {
                    chat
                }
            }

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
        _chats.value =
            _chats.value.map { chat ->
                if (chat.id == chatId) chat.copy(unreadCount = 0) else chat
            }
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
        _chats.value =
            _chats.value.map { chat ->
                if (chat.id == chatId && chat.draftText != trimmed) {
                    chat.copy(draftText = trimmed)
                } else {
                    chat
                }
            }
    }

    fun getChatDraft(chatId: Long): String = chatDrafts[chatId] ?: ""

    fun sendPhoto(
        chatId: Long,
        filePath: String,
        caption: String = "",
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
                }
            tdClient?.send(sendReq, this)
        } catch (e: Exception) {
            Log.e(tag, "Failed to send document via TDLib: ${e.message}")
        }
    }

    fun sendStickerEmoji(
        chatId: Long,
        stickerEmoji: String,
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
            }
        tdClient?.send(sendReq, this)
    }

    fun sendVideo(
        chatId: Long,
        filePath: String,
        durationSeconds: Int = 0,
        caption: String = "",
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
        _chats.value =
            _chats.value.map {
                if (it.id == chatId) it.copy(isArchived = newArchived, isPinned = if (newArchived) false else it.isPinned) else it
            }

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
        _chats.value =
            _chats.value.map {
                if (it.id == chatId) it.copy(folderName = cleanFolder) else it
            }

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
            _chats.value = listOf(secretChat) + _chats.value.filter { it.id != secretChat.id }
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
                    _chats.value = listOf(model) + _chats.value.filter { it.id != model.id }
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
        _chats.value =
            _chats.value.map {
                if (it.id == chatId) it.copy(selfDestructTimerSec = validSec) else it
            }

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
            onResult(cached)
            return
        }
        val defaultTopics =
            listOf(
                ForumTopicModel(topicId = 0L, name = "General (All Messages)", unreadCount = 0, iconEmoji = "💬"),
                ForumTopicModel(topicId = 101L, name = "Announcements", unreadCount = 0, iconEmoji = "📢"),
                ForumTopicModel(topicId = 102L, name = "Support & Q&A", unreadCount = 0, iconEmoji = "🛠️"),
            )
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
        val updated = existing + newTopic
        forumTopicsCache[chatId] = updated
        selectForumTopic(chatId, newTopic.topicId, newTopic.name)
        onResult(newTopic)
    }

    fun selectForumTopic(
        chatId: Long,
        topicId: Long,
        topicName: String,
    ) {
        _chats.value =
            _chats.value.map {
                if (it.id == chatId) {
                    it.copy(
                        isForum = true,
                        activeTopicId = topicId,
                        activeTopicName = if (topicId == 0L) "" else topicName,
                    )
                } else {
                    it
                }
            }
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
            _chats.value.find {
                it.title.equals("Saved Messages", ignoreCase = true) || it.id == 777000L
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
            _chats.value = (listOf(savedChat) + _chats.value.filter { it.id != savedChat.id }).sortedByDescending { it.isPinned }
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
                        val updated = (listOf(model) + _chats.value.filter { it.id != model.id }).sortedByDescending { it.isPinned }
                        _chats.value = updated
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
            _chats.value.find {
                it.title.equals(trimmed, ignoreCase = true) ||
                    it.title.equals(cleanUsername, ignoreCase = true)
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
            _chats.value = listOf(newChat) + _chats.value.filter { it.id != newChat.id }
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
            _chats.value = listOf(newChat) + _chats.value.filter { it.id != newChat.id }
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
                }
            tdClient?.send(sendReq, this)
        } catch (e: Exception) {
            Log.e(tag, "Failed to send voice note via TDLib: ${e.message}")
        }
    }

    fun sendLocation(
        chatId: Long,
        latitude: Double,
        longitude: Double,
        accuracy: Float,
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
                }
            tdClient?.send(sendReq, this)

            // Also send fallback text with Google Maps link for clients that don't render native geo points
            sendMessage(chatId, "📍 Location link: $gmapsLink")
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

        val updatedChats =
            _chats.value.map {
                if (it.id == chatId) it.copy(lastMessage = message.text, time = message.time) else it
            }
        _chats.value = updatedChats
    }

    fun loadChats(limit: Int = 30) {
        tdClient?.send(TdApi.GetChats(TdApi.ChatListMain(), limit), this)
    }

    fun loadMoreChats(step: Int = 20) {
        val currentCount = _chats.value.size
        tdClient?.send(TdApi.GetChats(TdApi.ChatListMain(), currentCount + step), this)
    }

    fun loadMessages(
        chatId: Long,
        fromMessageId: Long = 0,
        limit: Int = 30,
    ) {
        tdClient?.send(TdApi.GetChatHistory(chatId, fromMessageId, 0, limit, false), this)
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
            _chats.value.filter {
                it.title.lowercase().contains(lowerQuery) || it.lastMessage.lowercase().contains(lowerQuery)
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
                        _chats.value.filter {
                            it.title.lowercase().contains(lowerQuery) || it.lastMessage.lowercase().contains(lowerQuery)
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
            _chats.value.filter {
                it.chatType == ChatType.DIRECT && (it.title.lowercase().contains(lowerQuery) || it.lastMessage.lowercase().contains(lowerQuery))
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
                val chatTitle = _chats.value.find { it.id == cId }?.title ?: "Chat #$cId"
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
                    val chatTitle = _chats.value.find { it.id == cId }?.title ?: "Chat #$cId"
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
            } ?: _chats.value.find { it.id == userId }?.title ?: "Telegram Call"

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
            } ?: _chats.value.find { it.id == call.userId }?.title ?: "Telegram Call"

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
        val chatModel = _chats.value.find { it.id == chatId }

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
                val isMuted = chatObj?.notificationSettings?.muteFor != 0

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
        val muteFor = if (currentMuted) 0 else 31536000
        val settings =
            TdApi.ChatNotificationSettings().apply {
                this.useDefaultMuteFor = false
                this.muteFor = muteFor
            }
        val req = TdApi.SetChatNotificationSettings(chatId, settings)
        client.send(req) { obj ->
            val success = obj is TdApi.Ok
            scope.launch(Dispatchers.Main) {
                onResult?.invoke(if (success) !currentMuted else currentMuted)
            }
        }
    }

    fun logOut() {
        tdClient?.send(TdApi.LogOut(), this)
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
                when (val curr = _authState.value) {
                    is AuthState.WaitPhoneNumber -> _authState.value = curr.copy(errorMessage = err.message)
                    is AuthState.WaitCode -> _authState.value = curr.copy(errorMessage = err.message)
                    is AuthState.WaitPassword -> _authState.value = curr.copy(errorMessage = err.message)
                    is AuthState.WaitPasswordRecoveryCode -> _authState.value = curr.copy(errorMessage = err.message)
                    else -> {
                        if (_authState.value !is AuthState.Ready) {
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
                val dbDir = File(context.filesDir, "tdlib_${this@TelegramManager.apiId}").absolutePath
                val params =
                    TdApi.SetTdlibParameters().apply {
                        databaseDirectory = dbDir
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
                loadChats()
            }
            TdApi.AuthorizationStateClosed.CONSTRUCTOR -> {
                _authState.value = AuthState.Initial
                start()
            }
        }
    }

    private fun handleConnectionState(state: TdApi.ConnectionState) {
        val label =
            when (state.constructor) {
                TdApi.ConnectionStateConnecting.CONSTRUCTOR -> "Connecting..."
                TdApi.ConnectionStateConnectingToProxy.CONSTRUCTOR -> "Proxy..."
                TdApi.ConnectionStateUpdating.CONSTRUCTOR -> "Updating..."
                TdApi.ConnectionStateReady.CONSTRUCTOR -> "Online"
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
        val list = _chats.value.toMutableList()
        var updated = false
        for (i in list.indices) {
            val chat = list[i]
            val uId = chatIdToUserId[chat.id] ?: if (chat.chatType == ChatType.DIRECT) chat.id else null
            if (uId == user.id && chat.isPremium != user.isPremium) {
                list[i] = chat.copy(isPremium = user.isPremium)
                updated = true
            }
        }
        if (updated) {
            _chats.value = list
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

        val currentList = _chats.value.toMutableList()
        var updated = false
        for (i in currentList.indices) {
            val chat = currentList[i]
            if (chat.chatType == ChatType.DIRECT && chat.id == user.id) {
                if (chat.isPremium != user.isPremium) {
                    currentList[i] = chat.copy(isPremium = user.isPremium)
                    updated = true
                }
            }
        }
        if (updated) {
            _chats.value = currentList
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

        val existingModel = _chats.value.find { it.id == chat.id }
        val isPinnedChat = existingModel?.isPinned ?: false
        val isMutedChat = chat.notificationSettings?.muteFor != 0
        val draftStr = chatDrafts[chat.id] ?: ""

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
                        val currentList = _chats.value.toMutableList()
                        for (model in updates) {
                            val idx = currentList.indexOfFirst { it.id == model.id }
                            if (idx >= 0) {
                                currentList[idx] = model
                            } else {
                                currentList.add(model)
                            }
                        }
                        _chats.value = currentList
                    }
                }
        }
    }

    private fun updateChatLastMessage(
        chatId: Long,
        msg: TdApi.Message?,
    ) {
        val list = _chats.value.toMutableList()
        val index = list.indexOfFirst { it.id == chatId }
        if (index >= 0) {
            val old = list[index]
            val lastMsgText = extractMessageText(msg?.content)
            val timeStr = if (msg != null) formatTime(msg.date.toLong() * 1000) else old.time
            list[index] = old.copy(lastMessage = lastMsgText, time = timeStr)
            _chats.value = list
        }
    }

    private fun extractStickerInfo(content: TdApi.MessageContent?): Pair<String, Boolean> {
        if (content == null) return Pair("", false)
        if (content.constructor == TdApi.MessageSticker.CONSTRUCTOR) {
            val sticker = (content as TdApi.MessageSticker).sticker
            if (sticker != null) {
                val emoji = sticker.emoji ?: ""
                val isPremium =
                    try {
                        val field = sticker.javaClass.getField("isPremium")
                        field.getBoolean(sticker)
                    } catch (e: Throwable) {
                        try {
                            val fullTypeField = sticker.javaClass.getField("fullType")
                            val fullTypeObj = fullTypeField.get(sticker)
                            fullTypeObj != null && fullTypeObj.javaClass.simpleName.contains("Premium")
                        } catch (ex: Throwable) {
                            false
                        }
                    }
                return Pair(emoji, isPremium)
            }
        }
        return Pair("", false)
    }

    private fun handleMessageContentUpdated(
        chatId: Long,
        messageId: Long,
        newContent: TdApi.MessageContent?,
    ) {
        val newText = extractMessageText(newContent)
        val newCaption = extractMediaCaption(newContent)
        val currentMap = _messages.value.toMutableMap()
        val list = (currentMap[chatId] ?: return).toMutableList()
        val idx = list.indexOfFirst { it.id == messageId }
        if (idx >= 0) {
            list[idx] = list[idx].copy(text = newText, mediaCaption = newCaption, isEdited = true)
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
            )

        appendMessage(msg.chatId, model)
        if (!msg.isOutgoing && backgroundPushEnabled) {
            val chatTitle = _chats.value.find { it.id == msg.chatId }?.title ?: "Telegram"
            NotificationHelper.showIncomingMessageNotification(
                context = context,
                chatId = msg.chatId,
                chatTitle = chatTitle,
                senderName = model.senderName,
                messageText = model.text,
            )
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

        synchronized(pendingChats) {
            for ((id, chat) in pendingChats) {
                if (chat.avatarFileId == file.id) {
                    pendingChats[id] = chat.copy(avatarPath = file.local.path)
                }
            }
        }

        // 1. Check if matches any chat avatar
        var chatUpdated = false
        val currentChats =
            _chats.value.map { chat ->
                if (chat.avatarFileId == file.id && chat.avatarPath != file.local.path) {
                    chatUpdated = true
                    chat.copy(avatarPath = file.local.path)
                } else {
                    chat
                }
            }
        if (chatUpdated) {
            _chats.value = currentChats
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
        }
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
            else -> ""
        }
    }

    private fun extractMediaFileInfo(content: TdApi.MessageContent?): Pair<Int, String?> {
        if (content == null) return Pair(0, null)
        return when (content.constructor) {
            TdApi.MessagePhoto.CONSTRUCTOR -> {
                val photo = content as TdApi.MessagePhoto
                val size = photo.photo.sizes.lastOrNull()
                val f = size?.photo
                if (f != null) {
                    if (f.local.isDownloadingCompleted && f.local.path.isNotBlank()) {
                        Pair(f.id, f.local.path)
                    } else {
                        if (f.local.canBeDownloaded && !f.local.isDownloadingActive) {
                            tdClient?.send(TdApi.DownloadFile(f.id, 1, 0, 0, false), this)
                        }
                        Pair(f.id, null)
                    }
                } else {
                    Pair(0, null)
                }
            }
            TdApi.MessageVoiceNote.CONSTRUCTOR -> {
                val voice = (content as TdApi.MessageVoiceNote).voiceNote.voice
                if (voice.local.isDownloadingCompleted && voice.local.path.isNotBlank()) {
                    Pair(voice.id, voice.local.path)
                } else {
                    if (voice.local.canBeDownloaded && !voice.local.isDownloadingActive) {
                        tdClient?.send(TdApi.DownloadFile(voice.id, 1, 0, 0, false), this)
                    }
                    Pair(voice.id, null)
                }
            }
            TdApi.MessageAudio.CONSTRUCTOR -> {
                val audio = (content as TdApi.MessageAudio).audio.audio
                if (audio.local.isDownloadingCompleted && audio.local.path.isNotBlank()) {
                    Pair(audio.id, audio.local.path)
                } else {
                    if (audio.local.canBeDownloaded && !audio.local.isDownloadingActive) {
                        tdClient?.send(TdApi.DownloadFile(audio.id, 1, 0, 0, false), this)
                    }
                    Pair(audio.id, null)
                }
            }
            else -> Pair(0, null)
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
            else -> "[Message]"
        }
    }

    private fun formatTime(millis: Long): String {
        val df = SimpleDateFormat("HH:mm", Locale.getDefault())
        return df.format(Date(millis))
    }
}
