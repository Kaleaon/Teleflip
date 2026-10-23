package com.teleflip.app.telegram

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.teleflip.app.calls.AndroidCallAudioSession
import com.teleflip.app.calls.CallAudioSession
import com.teleflip.app.calls.CallTransport
import com.teleflip.app.calls.CallTransportController
import com.teleflip.app.calls.NtgCallTransport
import com.teleflip.app.calls.TransportState
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
import com.teleflip.app.model.MessageFormatting
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
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
import java.util.concurrent.Executors
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

class TelegramManager(
    paramContext: Context,
    private val transportFactory: (Context) -> CallTransport = { NtgCallTransport(it) },
    private val audioFactory: (Context) -> CallAudioSession = { AndroidCallAudioSession(it) },
    private val callTaskDispatcher: kotlinx.coroutines.CoroutineDispatcher? = null,
) : Client.ResultHandler {
    private val context: Context = paramContext.applicationContext ?: paramContext
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

    private val callDispatcher by lazy { callTaskDispatcher ?: Executors.newSingleThreadExecutor { task -> Thread(task, "TeleFlipCall").apply { isDaemon = true } }.asCoroutineDispatcher() }
    private val callAudio by lazy { audioFactory(context) }
    private var callClock: Job? = null
    private var acceptingCallId: Int? = null
    private var connectedAt: Long? = null

    @Volatile private var callAudioPrepared = false

    @Volatile private var callRequestPending = false

    @Volatile private var cancelCallRequest = false
    val hasOngoingCall: Boolean get() = callRequestPending || _activeCall.value?.state?.let { it != CallState.ERROR && it != CallState.DISCARDED } == true
    private val callTransportDelegate =
        lazy {
            CallTransportController(
                transportFactory(context),
                onSignaling = { id, data ->
                    tdClient?.send(TdApi.SendCallSignalingData(id, data)) { result ->
                        if (result is TdApi.Error) failCall(id, result.message)
                    }
                },
                onState = { id, state ->
                    scope.launch(Dispatchers.Main) {
                        val current = _activeCall.value
                        if (current?.callId == id && current.state != CallState.ERROR && current.state != CallState.DISCARDED && current.state != CallState.HANGING_UP) {
                            when (state) {
                                TransportState.CONNECTED -> {
                                    _activeCall.value = current.copy(state = CallState.READY)
                                    if (connectedAt == null) connectedAt = SystemClock.elapsedRealtime()
                                    if (callClock?.isActive != true) {
                                        callClock =
                                            scope.launch {
                                                while (_activeCall.value?.callId == id) {
                                                    _activeCall.update {
                                                        it?.takeIf {
                                                                call ->
                                                            call.callId == id
                                                        }?.copy(durationSec = ((SystemClock.elapsedRealtime() - (connectedAt ?: SystemClock.elapsedRealtime())) / 1000).toInt()) ?: it
                                                    }
                                                    delay(1000)
                                                }
                                            }
                                    }
                                }
                                TransportState.CONNECTING -> _activeCall.value = current.copy(state = CallState.CONNECTING)
                                else -> failCall(id, "Call audio connection ended")
                            }
                        }
                    }
                },
            )
        }

    private val callTransport get() = callTransportDelegate.value

    private fun prepareCallAudio() {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "Microphone permission is required for calls" }
        callAudio.start { _activeCall.value?.let { failCall(it.callId, "Call audio focus was lost") } }
        callAudioPrepared = true
        callAudio.speaker(_activeCall.value?.isSpeakerOn ?: false)
    }

    private fun stopPreparedCallAudio() {
        callAudioPrepared = false
        runCatching { callAudio.stop() }
    }

    private fun releaseCallAudio(id: Int) {
        if (acceptingCallId == id) acceptingCallId = null
        callClock?.cancel()
        callClock = null
        connectedAt = null
        if (callTransportDelegate.isInitialized()) scope.launch(callDispatcher) { runCatching { callTransport.stop(id) } }
        stopPreparedCallAudio()
    }

    fun onCallAudioServiceStopped() {
        if (callAudioPrepared) _activeCall.value?.let { failCall(it.callId, "Call audio service stopped") }
    }

    private fun failCall(
        id: Int,
        message: String,
    ) {
        scope.launch(Dispatchers.Main) {
            if (_activeCall.value?.callId != id || _activeCall.value?.state == CallState.DISCARDED || _activeCall.value?.state == CallState.ERROR) return@launch
            _activeCall.update { it?.copy(state = CallState.ERROR) }
            releaseCallAudio(id)
            reportOperationError(message)
            tdClient?.send(TdApi.DiscardCall(id, true, "", _activeCall.value?.durationSec ?: 0, false, 0L)) { }
        }
    }

    val features =
        TelegramFeatures { request, done ->
            val client = tdClient
            if (client == null) {
                done(TdApi.Error(-1, "TDLib is not connected"))
            } else {
                if (request is TdApi.SendMessage) {
                    val topic = _chats.value.find { it.id == request.chatId }?.activeTopicId ?: 0
                    if (topic != 0L && request.topicId == null) request.topicId = TdApi.MessageTopicForum(topic.toInt())
                }
                if (request is TdApi.SendMessageAlbum && request.topicId == null) {
                    val topic = _chats.value.find { it.id == request.chatId }?.activeTopicId ?: 0
                    if (topic != 0L) request.topicId = TdApi.MessageTopicForum(topic.toInt())
                }
                client.send(request) { done(it) }
            }
        }

    fun featureChat(chat: TdApi.Chat): ChatModel = cacheCreatedChat(chat)

    fun markChatUnread(
        chatId: Long,
        unread: Boolean,
        done: (Result<TdApi.Ok>) -> Unit,
    ) {
        features.markUnread(chatId, unread) { result ->
            if (result.isSuccess) {
                synchronized(pendingChats) { pendingChats[chatId]?.let { pendingChats[chatId] = it.copy(isMarkedUnread = unread) } }
                _chats.update { chats -> chats.map { if (it.id == chatId) it.copy(isMarkedUnread = unread) else it } }
            }
            done(result)
        }
    }

    fun deleteSelectedMessages(
        chatId: Long,
        ids: LongArray,
        forEveryone: Boolean,
        done: (Result<TdApi.Ok>) -> Unit,
    ) {
        features.delete(chatId, ids, forEveryone) { result ->
            if (result.isSuccess) _messages.update { map -> map + (chatId to map[chatId].orEmpty().filterNot { it.id in ids }) }
            done(result)
        }
    }

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
        clearCallSession()
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

        if (tdClient == null) {
            reportOperationError("Cannot send message: TDLib is not initialized")
            return
        }
        saveChatDraft(chatId, "")

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
        dispatchMessage(sendReq)
    }

    private val _operationError = MutableStateFlow<String?>(null)
    val operationError: StateFlow<String?> = _operationError.asStateFlow()

    fun clearOperationError() {
        _operationError.value = null
    }

    private fun reportOperationError(message: String) {
        _operationError.value = message
        Log.e(tag, message)
    }

    private fun dispatchMessage(request: TdApi.SendMessage) {
        val client = tdClient
        if (client == null) {
            reportOperationError("Cannot send message: TDLib is not initialized")
            return
        }
        val topicId = _chats.value.find { it.id == request.chatId }?.activeTopicId ?: 0L
        if (topicId != 0L) {
            request.topicId = TdApi.MessageTopicForum(topicId.toInt())
        }
        client.send(request) { result ->
            if (result is TdApi.Error) {
                reportOperationError("Message was not sent: ${result.message}")
            }
            // UpdateNewMessage owns insertion. The send callback may arrive after
            // UpdateMessageSendSucceeded and contain an obsolete pending ID.
        }
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

        val client = tdClient
        if (client == null) {
            onComplete?.invoke(false)
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
                if (result is TdApi.Error) reportOperationError(result.message)
                if (result is TdApi.Message) handleMessageContentUpdated(chatId, messageId, result.content)
                scope.launch(Dispatchers.Main) {
                    onComplete?.invoke(ok)
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to edit message via TDLib: ${e.message}")
            onComplete?.invoke(false)
        }
    }

    fun toggleMessageReaction(
        chatId: Long,
        messageId: Long,
        emoji: String,
    ) {
        if (emoji.isBlank()) return
        val client = tdClient
        if (client == null) {
            reportOperationError("TDLib is not initialized")
            return
        }
        val message = _messages.value[chatId]?.find { it.id == messageId } ?: return
        val added = emoji !in message.reactions
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
            client.send(funcObj) { result ->
                if (result is TdApi.Ok) {
                    _messages.update { current ->
                        current + (
                            chatId to
                                current[chatId].orEmpty().map { item ->
                                    if (item.id != messageId) {
                                        item
                                    } else {
                                        item.copy(
                                            reactions = if (added) (item.reactions + emoji).distinct() else item.reactions - emoji,
                                        )
                                    }
                                }
                        )
                    }
                } else if (result is TdApi.Error) {
                    reportOperationError(result.message)
                }
            }
        } catch (e: Throwable) {
            Log.w(tag, "Reaction API dispatch skipped: ${e.message}")
        }
    }

    fun toggleChatPin(
        chatId: Long,
        currentPinned: Boolean,
        onResult: ((Boolean) -> Unit)? = null,
    ) {
        val client = tdClient
        if (client == null) {
            reportOperationError("TDLib is not initialized")
            onResult?.invoke(currentPinned)
            return
        }
        val newValue = !currentPinned
        val request =
            TdApi.ToggleChatIsPinned().apply {
                chatList = TdApi.ChatListMain()
                this.chatId = chatId
                isPinned = newValue
            }
        client.send(request) { result ->
            if (result is TdApi.Ok) {
                _chats.update { chats -> chats.map { if (it.id == chatId) it.copy(isPinned = newValue) else it }.sortedByDescending { it.isPinned } }
            } else if (result is TdApi.Error) {
                reportOperationError(result.message)
            }
            scope.launch(Dispatchers.Main) { onResult?.invoke(if (result is TdApi.Ok) newValue else currentPinned) }
        }
    }

    fun togglePinMessage(
        chatId: Long,
        messageId: Long,
        currentPinned: Boolean,
        onResult: ((Boolean) -> Unit)? = null,
    ) {
        val client = tdClient
        if (client == null) {
            reportOperationError("TDLib is not initialized")
            onResult?.invoke(currentPinned)
            return
        }
        val newValue = !currentPinned
        val request =
            if (newValue) {
                TdApi.PinChatMessage().apply {
                    this.chatId = chatId
                    this.messageId = messageId
                }
            } else {
                TdApi.UnpinChatMessage(chatId, messageId)
            }
        client.send(request) { result ->
            if (result is TdApi.Ok) {
                _messages.update { current -> current + (chatId to current[chatId].orEmpty().map { if (it.id == messageId) it.copy(isPinned = newValue) else it }) }
                _chats.update { chats -> chats.map { if (it.id == chatId) it.copy(pinnedMessageId = if (newValue) messageId else 0L) else it } }
            } else if (result is TdApi.Error) {
                reportOperationError(result.message)
            }
            scope.launch(Dispatchers.Main) { onResult?.invoke(if (result is TdApi.Ok) newValue else currentPinned) }
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
        disableNotification: Boolean = false,
        schedulingState: TdApi.MessageSchedulingState? = null,
    ) {
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
            dispatchMessage(sendReq)
        } catch (e: Exception) {
            Log.e(tag, "Failed to send photo via TDLib: ${e.message}")
            reportOperationError(e.message ?: "Could not send message")
        }
    }

    fun sendDocument(
        chatId: Long,
        filePath: String,
        caption: String = "",
        disableNotification: Boolean = false,
        schedulingState: TdApi.MessageSchedulingState? = null,
    ) {
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
            dispatchMessage(sendReq)
        } catch (e: Exception) {
            Log.e(tag, "Failed to send document via TDLib: ${e.message}")
            reportOperationError(e.message ?: "Could not send message")
        }
    }

    fun sendStickerEmoji(
        chatId: Long,
        stickerEmoji: String,
        disableNotification: Boolean = false,
        schedulingState: TdApi.MessageSchedulingState? = null,
    ) {
        if (stickerEmoji.isBlank()) return
        val client = tdClient
        if (client == null) {
            reportOperationError("TDLib is not initialized")
            return
        }
        client.send(TdApi.GetStickers(TdApi.StickerTypeRegular(), stickerEmoji, 1, chatId)) { result ->
            val sticker = (result as? TdApi.Stickers)?.stickers?.firstOrNull()
            if (sticker != null) {
                sendSticker(chatId, sticker.sticker.id, stickerEmoji, disableNotification, schedulingState)
            } else {
                reportOperationError((result as? TdApi.Error)?.message ?: "No Telegram sticker found for $stickerEmoji")
            }
        }
    }

    fun sendLocalSticker(
        chatId: Long,
        filePath: String,
    ) {
        scope.launch {
            try {
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(filePath, bounds)
                check(bounds.outWidth > 0 && bounds.outHeight > 0) { "Cannot read sticker image" }
                val options =
                    android.graphics.BitmapFactory.Options().apply {
                        inSampleSize = com.teleflip.app.ui.calculateInSampleSize(bounds, 512, 512)
                    }
                val bitmap = android.graphics.BitmapFactory.decodeFile(filePath, options) ?: error("Cannot read sticker image")
                val scale = 512f / maxOf(bitmap.width, bitmap.height)
                val resized = android.graphics.Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
                val file = File.createTempFile("sticker_", ".webp", context.cacheDir)
                try {
                    file.outputStream().use {
                        @Suppress("DEPRECATION")
                        check(resized.compress(android.graphics.Bitmap.CompressFormat.WEBP, 90, it)) { "Could not encode sticker" }
                    }
                } finally {
                    if (resized !== bitmap) resized.recycle()
                    bitmap.recycle()
                }
                dispatchMessage(
                    TdApi.SendMessage().apply {
                        this.chatId = chatId
                        this.options = createSendOptions(false, null)
                        inputMessageContent =
                            TdApi.InputMessageSticker().apply {
                                sticker =
                                    TdApi.InputSticker().apply {
                                        this.sticker = TdApi.InputFileLocal(file.absolutePath)
                                        width = (bounds.outWidth * (512f / maxOf(bounds.outWidth, bounds.outHeight))).toInt().coerceAtLeast(1)
                                        height = (bounds.outHeight * (512f / maxOf(bounds.outWidth, bounds.outHeight))).toInt().coerceAtLeast(1)
                                    }
                                emoji = ""
                            }
                    },
                )
            } catch (e: Exception) {
                reportOperationError(e.message ?: "Could not send sticker")
            }
        }
    }

    fun sendSticker(
        chatId: Long,
        stickerFileId: Int,
        emoji: String = "",
        disableNotification: Boolean = false,
        schedulingState: TdApi.MessageSchedulingState? = null,
    ) {
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
            dispatchMessage(req)
        } catch (e: Exception) {
            Log.e(tag, "Failed to send sticker via TDLib: ${e.message}")
            reportOperationError(e.message ?: "Could not send message")
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

        if (client == null) {
            onResult(emptyList())
            return
        }

        try {
            client.send(TdApi.GetRecentStickers(false)) { obj ->
                if (obj is TdApi.Stickers) {
                    obj.stickers.filter { it.fullType is TdApi.StickerFullTypeCustomEmoji }.forEach { st ->
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
                            val finalItems = resultList.distinctBy { it.fileId }
                            scope.launch(Dispatchers.Main) { onResult(finalItems) }
                        } else {
                            for (info in setObj.sets) {
                                client.send(TdApi.GetStickerSet(info.id)) { set ->
                                    if (set is TdApi.StickerSet) {
                                        set.stickers.filter { it.fullType is TdApi.StickerFullTypeCustomEmoji }.forEach { st ->
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
                                        val finalItems = resultList.distinctBy { it.fileId }
                                        scope.launch(Dispatchers.Main) { onResult(finalItems) }
                                    }
                                }
                            }
                        }
                    } else {
                        val finalItems = resultList.distinctBy { it.fileId }
                        scope.launch(Dispatchers.Main) { onResult(finalItems) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to fetch custom emoji stickers: ${e.message}")
            onResult(emptyList())
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
            dispatchMessage(req)
        } catch (e: Exception) {
            Log.e(tag, "Failed to send saved animation via TDLib: ${e.message}")
            reportOperationError(e.message ?: "Could not send message")
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
            dispatchMessage(sendReq)
        } catch (e: Exception) {
            Log.e(tag, "Failed to send video via TDLib: ${e.message}")
            reportOperationError(e.message ?: "Could not send message")
        }
    }

    fun sendAnimation(
        chatId: Long,
        filePath: String,
        caption: String = "",
        disableNotification: Boolean = false,
        schedulingState: TdApi.MessageSchedulingState? = null,
    ) {
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
            dispatchMessage(sendReq)
        } catch (e: Exception) {
            Log.e(tag, "Failed to send GIF via TDLib: ${e.message}")
            reportOperationError(e.message ?: "Could not send message")
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
            dispatchMessage(sendReq)
        } catch (e: Throwable) {
            Log.w(tag, "Failed to dispatch TDLib InputMessagePoll: ${e.message}")
            reportOperationError(e.message ?: "Could not send message")
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
        val client = tdClient
        if (client == null) {
            reportOperationError("TDLib is not initialized")
            return
        }
        try {
            val req =
                TdApi.SetPollAnswer().apply {
                    this.chatId = chatId
                    this.messageId = messageId
                    this.optionIds = intArrayOf(optionIndex)
                }
            client.send(req) { result ->
                if (result is TdApi.Error) reportOperationError(result.message)
            }
        } catch (e: Exception) {
            Log.w(tag, "votePoll TDLib call failed: ${e.message}")
        }
    }

    fun stopPoll(
        chatId: Long,
        messageId: Long,
    ) {
        val client = tdClient
        if (client == null) {
            reportOperationError("TDLib is not initialized")
            return
        }
        try {
            val req =
                TdApi.StopPoll().apply {
                    this.chatId = chatId
                    this.messageId = messageId
                }
            client.send(req) { result ->
                if (result is TdApi.Error) reportOperationError(result.message)
            }
        } catch (e: Exception) {
            Log.w(tag, "stopPoll TDLib call failed: ${e.message}")
        }
    }

    fun toggleChatArchived(
        chatId: Long,
        currentArchived: Boolean,
        onResult: ((Boolean) -> Unit)? = null,
    ) {
        val client = tdClient
        if (client == null) {
            reportOperationError("TDLib is not initialized")
            onResult?.invoke(currentArchived)
            return
        }
        val newValue = !currentArchived
        val request = TdApi.AddChatToList(chatId, if (newValue) TdApi.ChatListArchive() else TdApi.ChatListMain())
        client.send(request) { result ->
            if (result is TdApi.Ok) {
                _chats.update { chats -> chats.map { if (it.id == chatId) it.copy(isArchived = newValue, isPinned = if (newValue) false else it.isPinned) else it } }
            } else if (result is TdApi.Error) {
                reportOperationError(result.message)
            }
            scope.launch(Dispatchers.Main) { onResult?.invoke(if (result is TdApi.Ok) newValue else currentArchived) }
        }
    }

    fun assignChatToFolder(
        chatId: Long,
        folderName: String,
    ) {
        scope.launch {
            try {
                val cleanName = folderName.trim()
                val folders = _chatFolders.value
                val target = folders.find { it.title.equals(cleanName, ignoreCase = true) }
                for (model in folders.filter { chatId in it.includedChatIds || it.id == target?.id }) {
                    val folder = sendQuery(TdApi.GetChatFolder(model.id)) as TdApi.ChatFolder
                    folder.includedChatIds =
                        if (model.id == target?.id) {
                            (folder.includedChatIds.toList() + chatId).distinct().toLongArray()
                        } else {
                            folder.includedChatIds.filter { it != chatId }.toLongArray()
                        }
                    if (model.id == target?.id) {
                        folder.excludedChatIds = folder.excludedChatIds.filter { it != chatId }.toLongArray()
                    }
                    sendQuery(TdApi.EditChatFolder(model.id, folder))
                    handleChatFolderReceived(folder)
                }
                if (target == null && cleanName.isNotEmpty()) {
                    val info =
                        sendQuery(
                            TdApi.CreateChatFolder(
                                TdApi.ChatFolder().apply {
                                    name = TdApi.ChatFolderName(TdApi.FormattedText(cleanName, emptyArray()), false)
                                    pinnedChatIds = longArrayOf()
                                    includedChatIds = longArrayOf(chatId)
                                    excludedChatIds = longArrayOf()
                                },
                            ),
                        ) as TdApi.ChatFolderInfo
                    val folder = sendQuery(TdApi.GetChatFolder(info.id)) as TdApi.ChatFolder
                    _chatFolders.update { it + ChatFolderModel(info.id, cleanName, setOf(chatId)) }
                    handleChatFolderReceived(folder)
                }
                _chats.update { chats -> chats.map { if (it.id == chatId) it.copy(folderName = cleanName) else it } }
            } catch (e: Exception) {
                reportOperationError(e.message ?: "Could not update chat folder")
            }
        }
    }

    fun startSecretChat(
        targetQueryOrName: String,
        userId: Long = 0L,
        onResult: (ChatModel?, String?) -> Unit,
    ) {
        if (tdClient == null) {
            onResult(null, "TDLib is not initialized")
            return
        }
        scope.launch {
            try {
                val resolvedId =
                    if (userId != 0L) {
                        userId
                    } else {
                        val chat = sendQuery(TdApi.SearchPublicChat(targetQueryOrName.trim().removePrefix("@"))) as TdApi.Chat
                        (chat.type as? TdApi.ChatTypePrivate)?.userId ?: error("Select a Telegram user")
                    }
                val chat = sendQuery(TdApi.CreateNewSecretChat(resolvedId)) as TdApi.Chat
                val model = cacheCreatedChat(chat)
                withContext(Dispatchers.Main) { onResult(model, null) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onResult(null, e.message ?: "Could not start secret chat") }
            }
        }
    }

    fun setSelfDestructTimer(
        chatId: Long,
        seconds: Int,
        onResult: ((Int) -> Unit)? = null,
    ) {
        scope.launch {
            try {
                val validSeconds = seconds.coerceAtLeast(0)
                sendQuery(TdApi.SetChatMessageAutoDeleteTime(chatId, validSeconds))
                _chats.update { chats -> chats.map { if (it.id == chatId) it.copy(selfDestructTimerSec = validSeconds) else it } }
                withContext(Dispatchers.Main) { onResult?.invoke(validSeconds) }
            } catch (e: Exception) {
                reportOperationError(e.message ?: "Could not update message timer")
            }
        }
    }

    fun loadForumTopics(
        chatId: Long,
        onResult: (List<ForumTopicModel>) -> Unit,
    ) {
        val client = tdClient
        if (client == null) {
            reportOperationError("Cannot load forum topics: TDLib is not initialized")
            onResult(forumTopicsCache[chatId].orEmpty())
            return
        }
        client.send(
            TdApi.GetForumTopics().apply {
                this.chatId = chatId
                query = ""
                limit = 100
            },
        ) { result ->
            val topics =
                (result as? TdApi.ForumTopics)?.topics?.map { topic ->
                    ForumTopicModel(
                        topicId = topic.info.forumTopicId.toLong(),
                        name = topic.info.name,
                        unreadCount = topic.unreadCount,
                        iconEmoji = "💬",
                        isClosed = topic.info.isClosed,
                        isPinned = topic.isPinned,
                    )
                }?.sortedByDescending { it.isPinned }
            if (topics != null) forumTopicsCache[chatId] = topics
            if (result is TdApi.Error) reportOperationError(result.message)
            scope.launch(Dispatchers.Main) { onResult(topics ?: forumTopicsCache[chatId].orEmpty()) }
        }
    }

    fun createForumTopic(
        chatId: Long,
        name: String,
        iconEmoji: String = "💬",
        onResult: (ForumTopicModel?, String?) -> Unit,
    ) {
        if (tdClient == null) {
            onResult(null, "TDLib is not initialized")
            return
        }
        scope.launch {
            try {
                val info =
                    sendQuery(
                        TdApi.CreateForumTopic().apply {
                            this.chatId = chatId
                            this.name = name.trim()
                            this.icon = TdApi.ForumTopicIcon(0x6FB9F0, 0L)
                        },
                    ) as TdApi.ForumTopicInfo
                val topic = ForumTopicModel(info.forumTopicId.toLong(), info.name, 0, iconEmoji)
                forumTopicsCache[chatId] = forumTopicsCache[chatId].orEmpty() + topic
                selectForumTopic(chatId, topic.topicId, topic.name)
                withContext(Dispatchers.Main) { onResult(topic, null) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onResult(null, e.message ?: "Could not create topic") }
            }
        }
    }

    fun toggleForumTopicPin(
        chatId: Long,
        topicId: Long,
        currentPinned: Boolean,
        onResult: ((Boolean, String?) -> Unit)? = null,
    ) {
        val newPinned = !currentPinned
        val existing = forumTopicsCache[chatId].orEmpty()
        val updated =
            existing.map {
                if (it.topicId == topicId) it.copy(isPinned = newPinned) else it
            }.sortedByDescending { it.isPinned }
        forumTopicsCache[chatId] = updated

        val client = tdClient
        if (client == null) {
            forumTopicsCache[chatId] = existing
            onResult?.invoke(currentPinned, "TDLib is not initialized")
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

    fun getGroupMembers(chatId: Long): List<GroupMember> = groupMembersCache[chatId].orEmpty()

    fun loadGroupMembers(
        chatId: Long,
        onResult: (List<GroupMember>, String?) -> Unit,
    ) {
        if (tdClient == null) {
            onResult(groupMembersCache[chatId].orEmpty(), "TDLib is not initialized")
            return
        }
        scope.launch {
            try {
                val chat = sendQuery(TdApi.GetChat(chatId)) as TdApi.Chat
                val members =
                    when (val type = chat.type) {
                        is TdApi.ChatTypeBasicGroup -> {
                            val info = sendQuery(TdApi.GetBasicGroupFullInfo(type.basicGroupId)) as TdApi.BasicGroupFullInfo
                            info.members.toList()
                        }
                        is TdApi.ChatTypeSupergroup -> {
                            val result =
                                sendQuery(
                                    TdApi.GetSupergroupMembers().apply {
                                        supergroupId = type.supergroupId
                                        filter = TdApi.SupergroupMembersFilterRecent()
                                        limit = 200
                                    },
                                ) as TdApi.ChatMembers
                            result.members.toList()
                        }
                        else -> error("This chat has no group members")
                    }
                val models =
                    members.mapNotNull { member ->
                        val userId = (member.memberId as? TdApi.MessageSenderUser)?.userId ?: return@mapNotNull null
                        val user = userCache[userId] ?: (sendQuery(TdApi.GetUser(userId)) as TdApi.User).also { userCache[userId] = it }
                        val creator = member.status is TdApi.ChatMemberStatusCreator
                        val admin = creator || member.status is TdApi.ChatMemberStatusAdministrator
                        GroupMember(
                            userId = userId,
                            name = "${user.firstName} ${user.lastName}".trim(),
                            role =
                                if (creator) {
                                    "Owner"
                                } else if (admin) {
                                    "Admin"
                                } else {
                                    "Member"
                                },
                            status = if (user.status is TdApi.UserStatusOnline) "online" else "offline",
                            isAdmin = admin,
                            isCreator = creator,
                        )
                    }
                groupMembersCache[chatId] = models
                withContext(Dispatchers.Main) { onResult(models, null) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onResult(groupMembersCache[chatId].orEmpty(), e.message) }
            }
        }
    }

    fun setChatMemberAdminRole(
        chatId: Long,
        userId: Long,
        makeAdmin: Boolean,
        onResult: ((Boolean) -> Unit)? = null,
    ) {
        scope.launch {
            try {
                val status: TdApi.ChatMemberStatus =
                    if (makeAdmin) {
                        val chat = sendQuery(TdApi.GetChat(chatId)) as TdApi.Chat
                        val me = sendQuery(TdApi.GetMe()) as TdApi.User
                        val self = sendQuery(TdApi.GetChatMember(chatId, TdApi.MessageSenderUser(me.id))) as TdApi.ChatMember
                        val rights =
                            when (val selfStatus = self.status) {
                                is TdApi.ChatMemberStatusCreator ->
                                    TdApi.ChatAdministratorRights().apply {
                                        canManageChat = true
                                        canDeleteMessages = true
                                        canInviteUsers = true
                                        canRestrictMembers = true
                                        canPromoteMembers = true
                                        canChangeInfo = true
                                        if ((chat.type as? TdApi.ChatTypeSupergroup)?.isChannel == true) {
                                            canPostMessages = true
                                            canEditMessages = true
                                        } else {
                                            canPinMessages = true
                                            canManageTopics = true
                                        }
                                    }
                                is TdApi.ChatMemberStatusAdministrator -> selfStatus.rights
                                else -> error("Administrator permission required")
                            }
                        TdApi.ChatMemberStatusAdministrator().apply {
                            canBeEdited = true
                            this.rights = rights
                        }
                    } else {
                        TdApi.ChatMemberStatusMember()
                    }
                sendQuery(TdApi.SetChatMemberStatus(chatId, TdApi.MessageSenderUser(userId), status))
                groupMembersCache.remove(chatId)
                withContext(Dispatchers.Main) { onResult?.invoke(true) }
            } catch (e: Exception) {
                reportOperationError(e.message ?: "Could not change member role")
                withContext(Dispatchers.Main) { onResult?.invoke(false) }
            }
        }
    }

    fun removeChatMember(
        chatId: Long,
        userId: Long,
        onResult: ((Boolean) -> Unit)? = null,
    ) {
        scope.launch {
            try {
                sendQuery(TdApi.SetChatMemberStatus(chatId, TdApi.MessageSenderUser(userId), TdApi.ChatMemberStatusLeft()))
                groupMembersCache.remove(chatId)
                withContext(Dispatchers.Main) { onResult?.invoke(true) }
            } catch (e: Exception) {
                reportOperationError(e.message ?: "Could not remove member")
                withContext(Dispatchers.Main) { onResult?.invoke(false) }
            }
        }
    }

    fun openSavedMessages(onResult: (ChatModel?, String?) -> Unit) {
        if (tdClient == null) {
            onResult(null, "TDLib is not initialized")
            return
        }
        scope.launch {
            try {
                val me = sendQuery(TdApi.GetMe()) as TdApi.User
                val chat = sendQuery(TdApi.CreatePrivateChat(me.id, false)) as TdApi.Chat
                val model = cacheCreatedChat(chat).copy(title = "Saved Messages")
                withContext(Dispatchers.Main) { onResult(model, null) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onResult(null, e.message ?: "Could not open Saved Messages") }
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
            onResult(null, "TDLib is not initialized")
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
            onResult(null, "TDLib is not initialized")
            return
        }

        scope.launch {
            try {
                val req =
                    TdApi.CreateNewSupergroupChat().apply {
                        this.title = trimmed
                        this.isChannel = isChannel
                        this.description = ""
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
            dispatchMessage(sendReq)
        } catch (e: Exception) {
            Log.e(tag, "Failed to send voice note via TDLib: ${e.message}")
            reportOperationError(e.message ?: "Could not send message")
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
            dispatchMessage(sendReq)
        } catch (e: Exception) {
            Log.e(tag, "Failed to send location via TDLib: ${e.message}")
            reportOperationError(e.message ?: "Could not send message")
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
        val client = tdClient
        if (client == null) {
            reportOperationError("Cannot retry message: TDLib is not initialized")
            return
        }
        client.send(
            TdApi.ResendMessages().apply {
                this.chatId = chatId
                messageIds = longArrayOf(messageId)
            },
        ) { result ->
            if (result is TdApi.Error) reportOperationError(result.message)
        }
    }

    private val requestedReplyPreviews = java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<Long, Long>>()

    private fun resolveReplyPreviewAsync(
        chatId: Long,
        replyToId: Long,
    ) {
        if (replyToId == 0L || !requestedReplyPreviews.add(chatId to replyToId)) return
        tdClient?.send(TdApi.GetMessage(chatId, replyToId)) { result ->
            val msg = result as? TdApi.Message ?: return@send
            val sender = getSenderDisplayName(msg)
            val snippet = extractMessageText(msg.content).replace('\n', ' ').trim().take(40)
            val preview = if (snippet.isNotBlank()) "$sender: $snippet" else sender
            if (preview.isNotBlank()) {
                _messages.update { current ->
                    val existing = current[chatId] ?: return@update current
                    if (existing.none { it.replyToMessageId == replyToId && it.replyToPreview.isBlank() }) {
                        current
                    } else {
                        current + (
                            chatId to
                                existing.map { m ->
                                    if (m.replyToMessageId == replyToId && m.replyToPreview.isBlank()) {
                                        m.copy(replyToPreview = preview)
                                    } else {
                                        m
                                    }
                                }
                        )
                    }
                }
            }
        }
    }

    private fun appendMessage(
        chatId: Long,
        message: MessageModel,
    ) {
        var needAsyncReplyLookup = false
        _messages.update { current ->
            val messages = current[chatId].orEmpty()
            val index = messages.indexOfFirst { it.id == message.id }
            val updated = messages.toMutableList()
            val replyTarget =
                if (message.replyToMessageId != 0L && message.replyToPreview.isBlank()) {
                    messages.find { it.id == message.replyToMessageId }
                } else {
                    null
                }
            val resolvedPreview =
                if (replyTarget != null) {
                    val snippet = replyTarget.text.ifBlank { replyTarget.mediaCaption }.replace('\n', ' ').trim().take(40)
                    "${replyTarget.senderName}: $snippet"
                } else {
                    if (message.replyToMessageId != 0L && message.replyToPreview.isBlank()) {
                        needAsyncReplyLookup = true
                    }
                    message.replyToPreview
                }
            val resolved =
                message.copy(
                    mediaFilePath = downloadedFilePaths[message.mediaFileId] ?: message.mediaFilePath,
                    replyToPreview = resolvedPreview,
                )
            if (index < 0) updated.add(resolved) else updated[index] = resolved
            current + (chatId to updated)
        }
        if (needAsyncReplyLookup) {
            resolveReplyPreviewAsync(chatId, message.replyToMessageId)
        }

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

    fun loadChatFolders() {
        _chatFolders.value.forEach { folder ->
            if (folder.id > 0) tdClient?.send(TdApi.GetChatFolder(folder.id), this)
        }
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
            if (info.id > 0) tdClient?.send(TdApi.GetChatFolder(info.id), this)
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
            return
        }
        _chatFolders.value = currentFolders

        if (includedSet.isNotEmpty()) {
            _chats.value =
                _chats.value.map { chat ->
                    if (includedSet.contains(chat.id)) {
                        chat.copy(folderName = title)
                    } else {
                        chat
                    }
                }
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
                val cachedChat = _chats.value.find { it.id == sender.chatId }
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

    private val pendingSenderUserIds = java.util.concurrent.ConcurrentHashMap<Long, MutableSet<Pair<Long, Long>>>()

    fun getSenderDisplayName(message: TdApi.Message): String {
        if (message.isOutgoing) return "You"
        when (val sender = message.senderId) {
            is TdApi.MessageSenderUser -> {
                val user = userCache[sender.userId]
                if (user != null) {
                    val fullName = "${user.firstName} ${user.lastName.orEmpty()}".trim()
                    return if (fullName.isNotBlank()) fullName else "Contact"
                } else if (sender.userId > 0L) {
                    if (message.chatId != 0L && message.id != 0L) {
                        pendingSenderUserIds.computeIfAbsent(sender.userId) {
                            java.util.concurrent.ConcurrentHashMap.newKeySet()
                        }.add(message.chatId to message.id)
                    }
                    tdClient?.send(TdApi.GetUser(sender.userId), this)
                }
            }
            is TdApi.MessageSenderChat -> {
                val chat = _chats.value.find { it.id == sender.chatId }
                if (chat != null) return chat.title
            }
        }
        return "Contact"
    }

    suspend fun downloadFileSuspending(
        fileId: Int,
        timeoutMs: Long = 30_000L,
    ): String? {
        if (fileId <= 0 || tdClient == null) return null
        return withTimeoutOrNull(timeoutMs) {
            val current = sendQuery(TdApi.GetFile(fileId)) as? TdApi.File
            current?.let { localFilePath(it) }?.let { return@withTimeoutOrNull it }
            val deferred = CompletableDeferred<String?>()
            val waiters = fileDownloadWaiters.computeIfAbsent(fileId) { CopyOnWriteArrayList() }
            waiters.add(deferred)
            try {
                requestFileDownload(fileId)
                deferred.await()
            } finally {
                waiters.remove(deferred)
                if (waiters.isEmpty()) fileDownloadWaiters.remove(fileId, waiters)
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
            rawMessages.filterNot { replacedMessageIds.containsKey(it.chatId to it.id) }.map { msg ->
                toMessageModel(msg).copy(senderName = getSenderDisplayName(msg))
            }

        withContext(Dispatchers.Main) {
            val currentMap = _messages.value.toMutableMap()
            val existing = currentMap[chatId] ?: emptyList()
            val merged = (existing + convertedList).distinctBy { it.id }.map { it.copy(mediaFilePath = downloadedFilePaths[it.mediaFileId] ?: it.mediaFilePath) }.sortedBy { it.id }
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
                rawMessages.reversed().map { msg -> toMessageModel(msg).copy(senderName = getSenderDisplayName(msg)) }
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
            val chatTitleMap = _chats.value.associate { it.id to it.title }
            for ((cId, msgList) in _messages.value) {
                val chatTitle = chatTitleMap[cId] ?: "Chat #$cId"
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
                val chatTitleMap = _chats.value.associate { it.id to it.title }
                for (msg in obj.messages) {
                    val cId = msg.chatId
                    val mId = msg.id
                    val text = extractMessageText(msg.content)
                    val time = formatTime(msg.date.toLong() * 1000)
                    val chatTitle = chatTitleMap[cId] ?: "Chat #$cId"
                    val sender = getSenderDisplayName(msg)

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
        if (_chats.value.any { it.id == chatId && it.isMarkedUnread }) markChatUnread(chatId, false) { }
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
            val chatTitleMap = _chats.value.associate { it.id to it.title }
            val chatTitle = chatTitleMap[chatId] ?: "Chat"
            val matches =
                localMsgs.filter {
                    it.text.lowercase().contains(lower) || it.mediaCaption.lowercase().contains(lower)
                }.map {
                    MessageSearchResult(
                        messageId = it.id,
                        chatId = it.chatId,
                        chatTitle = chatTitle,
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
                val chatTitleMap = _chats.value.associate { it.id to it.title }
                val chatTitle = chatTitleMap[chatId] ?: "Chat"
                for (msg in obj.messages) {
                    val text = extractMessageText(msg.content)
                    val time = formatTime(msg.date.toLong() * 1000)
                    val sender = getSenderDisplayName(msg)
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
        scope.launch {
            try {
                sendQuery(
                    TdApi.DeleteChatHistory().apply {
                        this.chatId = chatId
                        this.removeFromChatList = false
                        this.revoke = revoke
                    },
                )
                _messages.update { it + (chatId to emptyList()) }
                withContext(Dispatchers.Main) { onComplete?.invoke() }
            } catch (e: Exception) {
                reportOperationError(e.message ?: "Could not clear chat history")
            }
        }
    }

    // =========================================================================
    // Contact Management
    // =========================================================================
    fun loadContacts(onResult: (List<ContactModel>) -> Unit) {
        val client = tdClient
        if (client == null) {
            onResult(emptyList())
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

    fun terminateSession(
        sessionId: Long,
        onResult: (Boolean, String?) -> Unit,
    ) {
        val client = tdClient
        if (client == null) {
            onResult(false, "Not connected")
            return
        }
        client.send(TdApi.TerminateSession(sessionId)) { obj ->
            scope.launch(Dispatchers.Main) {
                if (obj is TdApi.Ok) {
                    onResult(true, null)
                } else if (obj is TdApi.Error) {
                    onResult(false, obj.message)
                } else {
                    onResult(false, "Failed to terminate session")
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
        if (fileId <= 0) return
        requestFileDownload(fileId)
    }

    private fun requestFileDownload(fileId: Int) {
        val client = tdClient
        if (client == null) {
            reportOperationError("Cannot download media: TDLib is not initialized")
            fileDownloadWaiters.remove(fileId)?.forEach { it.complete(null) }
            return
        }
        try {
            client.send(TdApi.DownloadFile(fileId, 32, 0, 0, false)) { result ->
                when (result) {
                    is TdApi.File -> handleFileUpdate(result)
                    is TdApi.Error -> {
                        fileDownloadWaiters.remove(fileId)?.forEach { it.complete(null) }
                        reportOperationError("Media download failed: ${result.message}")
                    }
                }
            }
        } catch (e: Exception) {
            fileDownloadWaiters.remove(fileId)?.forEach { it.complete(null) }
            reportOperationError(e.message ?: "Media download failed")
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
        if (hasOngoingCall) {
            onResult?.invoke(false, "Another call is already active")
            return
        }
        try {
            prepareCallAudio()
            callRequestPending = true
            cancelCallRequest = false
            val protocol = callTransport.protocol()
            val req = TdApi.CreateCall(userId, protocol, false)
            client.send(req) { result ->
                scope.launch(Dispatchers.Main) {
                    callRequestPending = false
                    if (result is TdApi.CallId) {
                        if (cancelCallRequest) {
                            client.send(TdApi.DiscardCall(result.id, false, "", 0, false, 0L)) { }
                            stopPreparedCallAudio()
                            onResult?.invoke(false, "Call canceled")
                            return@launch
                        }
                        if (_activeCall.value?.callId != result.id) {
                            _activeCall.value =
                                ActiveCall(
                                    callId = result.id,
                                    userId = userId,
                                    userName = userName,
                                    isOutgoing = true,
                                    state = CallState.PENDING,
                                    durationSec = 0,
                                )
                        }
                        onResult?.invoke(true, "Call initiated (ID: ${result.id})")
                    } else if (result is TdApi.Error) {
                        stopPreparedCallAudio()
                        onResult?.invoke(false, result.message)
                    } else {
                        stopPreparedCallAudio()
                        onResult?.invoke(false, "Call initiation returned unexpected result")
                    }
                }
            }
        } catch (e: Throwable) {
            callRequestPending = false
            if (_activeCall.value == null) stopPreparedCallAudio()
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
        if (acceptingCallId == callId) {
            onResult?.invoke(true, "Call acceptance is already in progress")
            return
        }
        if (_activeCall.value?.callId != callId || _activeCall.value?.state != CallState.PENDING) {
            onResult?.invoke(false, "This call is no longer available")
            return
        }
        val protocol =
            try {
                prepareCallAudio()
                callTransport.protocol()
            } catch (error: Throwable) {
                runCatching { callAudio.stop() }
                onResult?.invoke(false, error.message)
                reportOperationError(error.message ?: "Unable to start call audio")
                return
            }
        val req =
            TdApi.AcceptCall().apply {
                this.callId = callId
                this.protocol = protocol
            }
        acceptingCallId = callId
        client.send(req) { result ->
            scope.launch(Dispatchers.Main) {
                if (_activeCall.value?.callId != callId) return@launch
                if (result is TdApi.Ok) {
                    _activeCall.update { if (it?.callId == callId && it.state == CallState.PENDING) it.copy(state = CallState.EXCHANGING_KEYS) else it }
                    onResult?.invoke(true, "Call accepted")
                } else if (result is TdApi.Error) {
                    releaseCallAudio(callId)
                    onResult?.invoke(false, result.message)
                } else {
                    releaseCallAudio(callId)
                    onResult?.invoke(false, "Failed to accept call")
                }
            }
        }
    }

    fun endCurrentCall() {
        val call = _activeCall.value
        if (call != null) {
            discardCall(call.callId, call.durationSec)
        } else if (callRequestPending) {
            cancelCallRequest = true
            stopPreparedCallAudio()
        }
    }

    fun discardCall(
        callId: Int,
        durationSec: Int = 0,
        onResult: ((Boolean, String?) -> Unit)? = null,
    ) {
        if (_activeCall.value?.callId == callId && _activeCall.value?.state in setOf(CallState.HANGING_UP, CallState.DISCARDED, CallState.ERROR)) {
            onResult?.invoke(true, "Call is already ending")
            return
        }
        val client = tdClient
        if (_activeCall.value?.callId != callId) {
            onResult?.invoke(false, "This call is no longer active")
            return
        }
        if (client == null) {
            releaseCallAudio(callId)
            _activeCall.update { if (it?.callId == callId) it.copy(state = CallState.DISCARDED) else it }
            onResult?.invoke(false, "TDLib is not initialized")
            return
        }
        releaseCallAudio(callId)
        _activeCall.update { if (it?.callId == callId) it.copy(state = CallState.HANGING_UP) else it }
        val req =
            TdApi.DiscardCall().apply {
                this.callId = callId
                this.isDisconnected = false
                this.inviteLink = ""
                this.duration = durationSec
                this.isVideo = false
                this.connectionId = 0L
            }
        client.send(req) { result ->
            scope.launch(Dispatchers.Main) {
                if (result is TdApi.Ok) {
                    _activeCall.update { if (it?.callId == callId) it.copy(state = CallState.DISCARDED) else it }
                    scope.launch {
                        delay(1200L)
                        if (_activeCall.value?.callId == callId) _activeCall.value = null
                    }
                    onResult?.invoke(true, "Call discarded")
                } else if (result is TdApi.Error) {
                    _activeCall.update { if (it?.callId == callId) it.copy(state = CallState.ERROR) else it }
                    reportOperationError(result.message)
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
        try {
            if (isSpeakerOn != null) callAudio.speaker(isSpeakerOn)
            if (isMuted != null && callTransportDelegate.isInitialized()) {
                scope.launch(callDispatcher) {
                    try {
                        callTransport.setMuted(curr.callId, isMuted)
                    } catch (error: Throwable) {
                        failCall(curr.callId, error.message ?: "Unable to mute call")
                    }
                }
            }
        } catch (error: Exception) {
            reportOperationError(error.message ?: "Unable to change audio output")
            return
        }
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
        val previous = _activeCall.value
        val terminalUpdate = call.state is TdApi.CallStateDiscarded || call.state is TdApi.CallStateError
        if (previous?.callId == call.id && (previous.state == CallState.ERROR || previous.state == CallState.DISCARDED || previous.state == CallState.HANGING_UP) && !terminalUpdate) return
        if (callRequestPending && !call.isOutgoing) {
            tdClient?.send(TdApi.DiscardCall(call.id, false, "", 0, call.isVideo, 0L)) { }
            return
        }
        if (previous != null && previous.callId != call.id && previous.state != CallState.ERROR && previous.state != CallState.DISCARDED) {
            tdClient?.send(TdApi.DiscardCall(call.id, false, "", 0, false, 0L)) { }
            return
        }
        val state =
            when (call.state.constructor) {
                TdApi.CallStatePending.CONSTRUCTOR -> CallState.PENDING
                TdApi.CallStateExchangingKeys.CONSTRUCTOR -> CallState.EXCHANGING_KEYS
                TdApi.CallStateReady.CONSTRUCTOR -> if (_activeCall.value?.callId == call.id && _activeCall.value?.state == CallState.READY) CallState.READY else CallState.CONNECTING
                TdApi.CallStateHangingUp.CONSTRUCTOR -> CallState.HANGING_UP
                TdApi.CallStateDiscarded.CONSTRUCTOR -> CallState.DISCARDED
                TdApi.CallStateError.CONSTRUCTOR -> CallState.ERROR
                else -> CallState.PENDING
            }

        val userName =
            userCache[call.userId]?.let { u ->
                if (!u.lastName.isNullOrBlank()) "${u.firstName} ${u.lastName}" else u.firstName
            } ?: _chats.value.find { it.id == call.userId }?.title ?: "Telegram Call"

        val existing = _activeCall.value?.takeIf { it.callId == call.id }
        val updated =
            ActiveCall(
                callId = call.id,
                userId = call.userId,
                userName = userName,
                isOutgoing = call.isOutgoing,
                state = state,
                durationSec = existing?.durationSec ?: 0,
                verificationEmojis = (call.state as? TdApi.CallStateReady)?.emojis?.toList() ?: existing?.verificationEmojis.orEmpty(),
                isMuted = existing?.isMuted ?: false,
                isSpeakerOn = existing?.isSpeakerOn ?: false,
            )
        _activeCall.value = updated
        if (state != CallState.PENDING) {
            runCatching { (context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager)?.cancel(900000 + call.id) }
        }
        if (call.state is TdApi.CallStateReady) {
            scope.launch(callDispatcher) {
                try {
                    val current = _activeCall.value
                    if (current?.callId != call.id || current.state == CallState.ERROR || current.state == CallState.DISCARDED || current.state == CallState.HANGING_UP) return@launch
                    callTransport.select(call.id)
                    (call.state as? TdApi.CallStateReady)?.let { ready ->
                        callTransport.setMuted(call.id, current.isMuted)
                        callTransport.start(call.id, call.isOutgoing, ready)
                    }
                } catch (error: Throwable) {
                    failCall(call.id, error.message ?: "Unable to start call transport")
                }
            }
        } else if (state == CallState.DISCARDED || state == CallState.ERROR || state == CallState.HANGING_UP) {
            releaseCallAudio(call.id)
        }
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
        val chat = _chats.value.find { it.id == chatId }
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
        val existingChat = _chats.value.find { it.id == chatId }
        val chatType = existingChat?.chatType ?: ChatType.DIRECT
        val isMuted = isNotificationSettingsMuted(notificationSettings, chatType)
        persistChatMuted(chatId, isMuted)
        if (isMuted) {
            NotificationHelper.cancelMessageNotification(context, chatId)
        }
        _chats.value =
            _chats.value.map { chat ->
                if (chat.id == chatId) {
                    if (chat.isMuted != isMuted) chat.copy(isMuted = isMuted) else chat
                } else {
                    chat
                }
            }
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
        _chats.value =
            _chats.value.map { chat ->
                val cached = chatNotificationSettingsCache[chat.id]
                if (cached != null && cached.useDefaultMuteFor) {
                    val newMuted = isNotificationSettingsMuted(cached, chat.chatType)
                    persistChatMuted(chat.id, newMuted)
                    if (newMuted) {
                        NotificationHelper.cancelMessageNotification(context, chat.id)
                    }
                    if (chat.isMuted != newMuted) chat.copy(isMuted = newMuted) else chat
                } else {
                    chat
                }
            }
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
        val client = tdClient
        val previousMuted = isChatMuted(chatId)
        if (client == null) {
            reportOperationError("TDLib is not initialized")
            onResult?.invoke(previousMuted)
            return
        }
        val isMuted = muteForSeconds > 0
        persistChatMuted(chatId, isMuted)
        if (isMuted) {
            NotificationHelper.cancelMessageNotification(context, chatId)
        }
        _chats.value =
            _chats.value.map {
                if (it.id == chatId) it.copy(isMuted = isMuted) else it
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
                val revertedMuted = previousMuted
                persistChatMuted(chatId, revertedMuted)
                scope.launch(Dispatchers.Main) {
                    _chats.value =
                        _chats.value.map {
                            if (it.id == chatId) it.copy(isMuted = revertedMuted) else it
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

    private fun clearCallSession() {
        cancelCallRequest = true
        callRequestPending = false
        _activeCall.value?.let { releaseCallAudio(it.callId) }
        if (callAudioPrepared) stopPreparedCallAudio()
        _activeCall.value = null
        paidMediaQuotes.clear()
    }

    fun logOut() {
        clearCallSession()
        synchronized(clientLock) {
            reconnectPending = false
        }
        tdClient?.send(TdApi.LogOut(), this)
    }

    fun close() {
        clearCallSession()
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
            TdApi.UpdateChatIsMarkedAsUnread.CONSTRUCTOR -> {
                val update = obj as TdApi.UpdateChatIsMarkedAsUnread
                synchronized(pendingChats) { pendingChats[update.chatId]?.let { pendingChats[update.chatId] = it.copy(isMarkedUnread = update.isMarkedAsUnread) } }
                _chats.update { chats -> chats.map { if (it.id == update.chatId) it.copy(isMarkedUnread = update.isMarkedAsUnread) else it } }
            }
            TdApi.UpdateNewCallSignalingData.CONSTRUCTOR -> {
                val update = obj as TdApi.UpdateNewCallSignalingData
                scope.launch(Dispatchers.Main) {
                    if (_activeCall.value?.callId != update.callId) return@launch
                    scope.launch(callDispatcher) {
                        try {
                            if (_activeCall.value?.callId == update.callId) {
                                callTransport.select(update.callId)
                                callTransport.receiveSignaling(update.callId, update.data)
                            }
                        } catch (error: Throwable) {
                            failCall(update.callId, error.message ?: "Invalid call signaling")
                        }
                    }
                }
            }
            TdApi.UpdateCall.CONSTRUCTOR -> {
                val updateCall = obj as TdApi.UpdateCall
                scope.launch(Dispatchers.Main) { handleCallUpdate(updateCall.call) }
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
            TdApi.UpdatePoll.CONSTRUCTOR -> {
                val poll = (obj as TdApi.UpdatePoll).poll
                _messages.update { current ->
                    current.mapValues { (chatId, messages) ->
                        messages.map { message ->
                            if (pollMessageIds[chatId to message.id] == poll.id) {
                                message.copy(
                                    pollQuestion = poll.question.text,
                                    pollOptions = pollOptions(poll),
                                    pollIsAnonymous = poll.isAnonymous,
                                    pollIsClosed = poll.isClosed,
                                    pollTotalVoters = poll.totalVoterCount,
                                )
                            } else {
                                message
                            }
                        }
                    }
                }
            }
            TdApi.UpdateMessageIsPinned.CONSTRUCTOR -> {
                val update = obj as TdApi.UpdateMessageIsPinned
                _messages.update { current ->
                    current + (
                        update.chatId to
                            current[update.chatId].orEmpty().map {
                                if (it.id == update.messageId) it.copy(isPinned = update.isPinned) else it
                            }
                    )
                }
            }
            TdApi.UpdateDeleteMessages.CONSTRUCTOR -> {
                val update = obj as TdApi.UpdateDeleteMessages
                if (update.isPermanent) {
                    val deleted = update.messageIds.toSet()
                    _messages.update { current -> current + (update.chatId to current[update.chatId].orEmpty().filterNot { it.id in deleted }) }
                }
            }
            TdApi.UpdateMessageContent.CONSTRUCTOR -> {
                val updateContent = obj as TdApi.UpdateMessageContent
                handleMessageContentUpdated(updateContent.chatId, updateContent.messageId, updateContent.newContent)
            }
            TdApi.UpdateMessageEdited.CONSTRUCTOR -> {
                val updateEdited = obj as TdApi.UpdateMessageEdited
                handleMessageEditedFlag(updateEdited.chatId, updateEdited.messageId, updateEdited.editDate)
                val hasButtons = updateEdited.replyMarkup is TdApi.ReplyMarkupInlineKeyboard || updateEdited.replyMarkup is TdApi.ReplyMarkupShowKeyboard
                _messages.update {
                        current ->
                    current + (updateEdited.chatId to current[updateEdited.chatId].orEmpty().map { if (it.id == updateEdited.messageId) it.copy(hasBotButtons = hasButtons) else it })
                }
            }
            TdApi.UpdateChatReadOutbox.CONSTRUCTOR -> {
                val update = obj as TdApi.UpdateChatReadOutbox
                lastReadOutboxIds[update.chatId] = update.lastReadOutboxMessageId
                _messages.update { current ->
                    val existing = current[update.chatId] ?: return@update current
                    if (existing.none { it.isOutbound && it.status == MessageStatus.SENT && it.id <= update.lastReadOutboxMessageId }) {
                        current
                    } else {
                        current + (
                            update.chatId to
                                existing.map { message ->
                                    if (message.isOutbound && message.status == MessageStatus.SENT && message.id <= update.lastReadOutboxMessageId) {
                                        message.copy(status = MessageStatus.READ)
                                    } else {
                                        message
                                    }
                                }
                        )
                    }
                }
            }
            TdApi.UpdateMessageSendSucceeded.CONSTRUCTOR -> {
                val update = obj as TdApi.UpdateMessageSendSucceeded
                reconcileSentMessage(update.oldMessageId, update.message)
            }
            TdApi.UpdateMessageSendFailed.CONSTRUCTOR -> {
                val update = obj as TdApi.UpdateMessageSendFailed
                reconcileSentMessage(update.oldMessageId, update.message)
                reportOperationError("Message failed to send: ${update.error.message}")
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
                clearCallSession()
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
        val fullName = "${user.firstName} ${user.lastName.orEmpty()}".trim()
        if (fullName.isNotBlank()) {
            val pendingRefs = pendingSenderUserIds.remove(user.id)
            if (!pendingRefs.isNullOrEmpty()) {
                _messages.update { current ->
                    current.mapValues { (cId, msgList) ->
                        if (pendingRefs.any { it.first == cId }) {
                            msgList.map { m ->
                                if (pendingRefs.contains(cId to m.id) && !m.isOutbound) {
                                    m.copy(senderName = fullName)
                                } else {
                                    m
                                }
                            }
                        } else {
                            msgList
                        }
                    }
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

    private fun cacheCreatedChat(chat: TdApi.Chat): ChatModel {
        addOrUpdateChat(chat)
        val model =
            synchronized(pendingChats) { pendingChats[chat.id] }
                ?: _chats.value.first { it.id == chat.id }
        _chats.update { chats -> listOf(model) + chats.filterNot { it.id == model.id } }
        return model
    }

    private fun addOrUpdateChat(chat: TdApi.Chat) {
        lastReadOutboxIds[chat.id] = chat.lastReadOutboxMessageId
        val title = chat.title.ifBlank { "Chat #${chat.id}" }
        val lastMsg = chat.lastMessage
        val lastMsgText = extractMessageText(lastMsg?.content)
        val timeStr = if (lastMsg != null) formatTime(lastMsg.date.toLong() * 1000) else ""

        val type =
            when (chat.type.constructor) {
                TdApi.ChatTypePrivate.CONSTRUCTOR, TdApi.ChatTypeSecret.CONSTRUCTOR -> ChatType.DIRECT
                TdApi.ChatTypeBasicGroup.CONSTRUCTOR -> ChatType.GROUP
                TdApi.ChatTypeSupergroup.CONSTRUCTOR -> if ((chat.type as TdApi.ChatTypeSupergroup).isChannel) ChatType.CHANNEL else ChatType.GROUP
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
            val resolvedAvatar =
                localFilePath(small) ?: downloadedFilePaths[small.id]?.takeIf { File(it).isFile }
            if (resolvedAvatar != null) {
                avatarPath = resolvedAvatar
            } else if (small.local.canBeDownloaded && !small.local.isDownloadingActive) {
                tdClient?.send(TdApi.DownloadFile(small.id, 32, 0, 0, false), this)
            }
        }

        val existingModel = _chats.value.find { it.id == chat.id }
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
                isSecretChat = chat.type is TdApi.ChatTypeSecret,
                secretChatId = (chat.type as? TdApi.ChatTypeSecret)?.secretChatId ?: 0,
                title = title,
                lastMessage = lastMsgText,
                time = timeStr,
                unreadCount = chat.unreadCount,
                isMarkedUnread = chat.isMarkedAsUnread,
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

    private fun extractFormatting(content: TdApi.MessageContent?): List<MessageFormatting> {
        val formatted =
            when (content) {
                is TdApi.MessageText -> content.text
                is TdApi.MessagePhoto -> content.caption
                is TdApi.MessageVideo -> content.caption
                is TdApi.MessageAnimation -> content.caption
                is TdApi.MessageDocument -> content.caption
                is TdApi.MessageAudio -> content.caption
                is TdApi.MessageVoiceNote -> content.caption
                is TdApi.MessagePaidMedia -> content.caption
                else -> null
            }
        return formatted?.entities.orEmpty().mapNotNull { entity ->
            val kind =
                when (entity.type) {
                    is TdApi.TextEntityTypeBold -> "bold"
                    is TdApi.TextEntityTypeItalic -> "italic"
                    is TdApi.TextEntityTypeUnderline -> "underline"
                    is TdApi.TextEntityTypeStrikethrough -> "strike"
                    is TdApi.TextEntityTypeCode, is TdApi.TextEntityTypePre, is TdApi.TextEntityTypePreCode -> "code"
                    is TdApi.TextEntityTypeSpoiler -> "spoiler"
                    is TdApi.TextEntityTypeUrl, is TdApi.TextEntityTypeTextUrl -> "url"
                    else -> return@mapNotNull null
                }
            val start = entity.offset.toLong()
            val end = start + entity.length
            if (start < 0 || entity.length <= 0 || end > formatted!!.text.length) return@mapNotNull null
            val url = (entity.type as? TdApi.TextEntityTypeTextUrl)?.url ?: if (kind == "url") formatted!!.text.substring(entity.offset, end.toInt()) else ""
            MessageFormatting(entity.offset, entity.length, kind, url)
        }
    }

    private fun extractMediaMimeType(content: TdApi.MessageContent?): String =
        when (content) {
            is TdApi.MessageDocument -> content.document.mimeType.orEmpty()
            is TdApi.MessageAudio -> content.audio.mimeType.orEmpty()
            is TdApi.MessageVideo -> content.video.mimeType.orEmpty()
            is TdApi.MessageAnimation -> content.animation.mimeType.orEmpty()
            is TdApi.MessagePaidMedia ->
                when (val paid = content.media.firstOrNull()) {
                    is TdApi.PaidMediaVideo -> paid.video.mimeType.orEmpty().ifBlank { "video/mp4" }
                    else -> ""
                }
            else -> ""
        }

    private fun handleMessageContentUpdated(
        chatId: Long,
        messageId: Long,
        newContent: TdApi.MessageContent?,
    ) {
        if (newContent == null) return
        val newText = extractMessageText(newContent)
        val newCaption = extractMediaCaption(newContent)
        val newMediaType = extractMediaType(newContent)
        val newMediaInfo = extractMediaFileInfo(newContent)
        val starCount = extractStarCount(newContent)
        val isLocked = extractIsPaidMediaLocked(newContent)
        val newTranscript = extractVoiceTranscript(newContent)
        _messages.update { current ->
            val list = current[chatId] ?: return@update current
            current + (
                chatId to
                    list.map { old ->
                        if (old.id != messageId) {
                            old
                        } else {
                            old.copy(
                                text = if (newText.isNotBlank()) newText else old.text,
                                mediaCaption = newCaption,
                                formatting = extractFormatting(newContent),
                                mediaType = newMediaType,
                                mediaFileId = newMediaInfo.first,
                                mediaFilePath = newMediaInfo.second ?: downloadedFilePaths[newMediaInfo.first] ?: old.mediaFilePath.takeIf { old.mediaFileId == newMediaInfo.first },
                                mediaMimeType = extractMediaMimeType(newContent),
                                starCount = starCount,
                                isPaidMediaLocked = isLocked,
                                transcript = if (newTranscript.isNotBlank()) newTranscript else old.transcript,
                                isEdited = true,
                            )
                        }
                    }
            )
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

    private fun messageStatus(msg: TdApi.Message): MessageStatus =
        when {
            !msg.isOutgoing -> MessageStatus.READ
            msg.sendingState is TdApi.MessageSendingStatePending -> MessageStatus.SENDING
            msg.sendingState is TdApi.MessageSendingStateFailed -> MessageStatus.FAILED
            msg.id <= (lastReadOutboxIds[msg.chatId] ?: 0L) -> MessageStatus.READ
            else -> MessageStatus.SENT
        }

    private val lastReadOutboxIds = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    private val replacedMessageIds = java.util.concurrent.ConcurrentHashMap<Pair<Long, Long>, Long>()

    private fun reconcileSentMessage(
        oldMessageId: Long,
        message: TdApi.Message,
    ) {
        if (message.schedulingState != null) return
        if (oldMessageId != message.id) replacedMessageIds[message.chatId to oldMessageId] = message.id
        val model = toMessageModel(message)
        _messages.update { current ->
            val messages = current[message.chatId].orEmpty()
            val index = messages.indexOfFirst { it.id == oldMessageId || it.id == message.id }
            val updated = messages.filterNot { it.id == oldMessageId || it.id == message.id }.toMutableList()
            val resolved = model.copy(mediaFilePath = downloadedFilePaths[model.mediaFileId] ?: model.mediaFilePath)
            updated.add(if (index < 0) updated.size else index.coerceAtMost(updated.size), resolved)
            current + (message.chatId to updated)
        }
    }

    private val pollMessageIds = java.util.concurrent.ConcurrentHashMap<Pair<Long, Long>, Long>()

    private fun pollOptions(poll: TdApi.Poll): List<PollOption> =
        poll.options.map { option ->
            PollOption(option.text.text, option.voterCount, option.votePercentage, option.isChosen)
        }

    private fun extractForwardedFrom(forwardInfo: TdApi.MessageForwardInfo?): String {
        val origin = forwardInfo?.origin ?: return ""
        return when (origin) {
            is TdApi.MessageOriginUser -> {
                val user = userCache[origin.senderUserId]
                if (user != null) {
                    "${user.firstName} ${user.lastName.orEmpty()}".trim().ifBlank { "User" }
                } else {
                    if (origin.senderUserId > 0L) tdClient?.send(TdApi.GetUser(origin.senderUserId), this)
                    "User"
                }
            }
            is TdApi.MessageOriginHiddenUser -> origin.senderName.ifBlank { "Hidden User" }
            is TdApi.MessageOriginChat -> {
                _chats.value.find { it.id == origin.senderChatId }?.title
                    ?: origin.authorSignature.ifBlank { "Chat" }
            }
            is TdApi.MessageOriginChannel -> {
                _chats.value.find { it.id == origin.chatId }?.title
                    ?: origin.authorSignature.ifBlank { "Channel" }
            }
            else -> ""
        }
    }

    private fun toMessageModel(msg: TdApi.Message): MessageModel {
        (msg.content as? TdApi.MessagePoll)?.poll?.let { pollMessageIds[msg.chatId to msg.id] = it.id }
        val mediaType = extractMediaType(msg.content)
        val mediaInfo = extractMediaFileInfo(msg.content)
        val stickerInfo = extractStickerInfo(msg.content)
        val model =
            MessageModel(
                id = msg.id,
                chatId = msg.chatId,
                senderName = getSenderDisplayName(msg),
                text = extractMessageText(msg.content),
                time = formatTime(msg.date.toLong() * 1000),
                isOutbound = msg.isOutgoing,
                status = messageStatus(msg),
                mediaType = mediaType,
                formatting = extractFormatting(msg.content),
                hasBotButtons = msg.replyMarkup is TdApi.ReplyMarkupInlineKeyboard || msg.replyMarkup is TdApi.ReplyMarkupShowKeyboard,
                mediaCaption = extractMediaCaption(msg.content),
                mediaFilePath = mediaInfo.second,
                mediaFileId = mediaInfo.first,
                mediaMimeType = extractMediaMimeType(msg.content),
                stickerFormat =
                    when ((msg.content as? TdApi.MessageSticker)?.sticker?.format) {
                        is TdApi.StickerFormatTgs -> "tgs"
                        is TdApi.StickerFormatWebm -> "webm"
                        else -> "webp"
                    },
                stickerEmoji = stickerInfo.first,
                isPremiumSticker = stickerInfo.second,
                starCount = extractStarCount(msg.content),
                isPaidMediaLocked = extractIsPaidMediaLocked(msg.content),
                transcript = extractVoiceTranscript(msg.content),
                durationSeconds =
                    when (val content = msg.content) {
                        is TdApi.MessageVoiceNote -> content.voiceNote?.duration ?: 0
                        is TdApi.MessageAudio -> content.audio?.duration ?: 0
                        is TdApi.MessageVideo -> content.video?.duration ?: 0
                        is TdApi.MessageVideoNote -> content.videoNote?.duration ?: 0
                        is TdApi.MessageAnimation -> content.animation?.duration ?: 0
                        else -> 0
                    },
                latitude = (msg.content as? TdApi.MessageLocation)?.location?.latitude ?: 0.0,
                longitude = (msg.content as? TdApi.MessageLocation)?.location?.longitude ?: 0.0,
                pollQuestion = (msg.content as? TdApi.MessagePoll)?.poll?.question?.text.orEmpty(),
                pollOptions =
                    (msg.content as? TdApi.MessagePoll)?.poll?.options?.map { option ->
                        PollOption(option.text.text, option.voterCount, option.votePercentage, option.isChosen)
                    }.orEmpty(),
                pollIsAnonymous = (msg.content as? TdApi.MessagePoll)?.poll?.isAnonymous ?: true,
                pollIsClosed = (msg.content as? TdApi.MessagePoll)?.poll?.isClosed ?: false,
                pollTotalVoters = (msg.content as? TdApi.MessagePoll)?.poll?.totalVoterCount ?: 0,
                messageThreadId = (msg.topicId as? TdApi.MessageTopicForum)?.forumTopicId?.toLong() ?: 0L,
                replyToMessageId = (msg.replyTo as? TdApi.MessageReplyToMessage)?.messageId ?: 0L,
                replyToPreview =
                    (msg.replyTo as? TdApi.MessageReplyToMessage)?.let { reply ->
                        reply.quote?.text?.text ?: extractMessageText(reply.content)
                    }.orEmpty(),
                forwardedFrom = extractForwardedFrom(msg.forwardInfo),
                selfDestructSeconds = (msg.selfDestructType as? TdApi.MessageSelfDestructTypeTimer)?.selfDestructTime ?: 0,
                isEdited = msg.editDate > 0,
                isPinned = msg.isPinned,
            )

        return model
    }

    private fun handleIncomingMessage(msg: TdApi.Message) {
        if (msg.schedulingState != null) return
        if (replacedMessageIds.containsKey(msg.chatId to msg.id)) return
        val alreadyPresent = _messages.value[msg.chatId].orEmpty().any { it.id == msg.id }
        val model = toMessageModel(msg)
        appendMessage(msg.chatId, model)
        if (!alreadyPresent && !msg.isOutgoing && backgroundPushEnabled) {
            val targetChat = _chats.value.find { it.id == msg.chatId }
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
        val list = msgs.reversed().filterNot { it.schedulingState != null || replacedMessageIds.containsKey(it.chatId to it.id) }.map { toMessageModel(it) }
        val unresolvedReplyIds = mutableSetOf<Long>()
        _messages.update { current ->
            val existing = current[chatId].orEmpty()
            // Live updates already in memory take precedence over a history response.
            val rawMerged = (existing + list).distinctBy { it.id }.sortedBy { it.id }
            val byId = rawMerged.associateBy { it.id }
            val merged =
                rawMerged.map { item ->
                    val resolvedPath = downloadedFilePaths[item.mediaFileId] ?: item.mediaFilePath
                    val resolvedPreview =
                        if (item.replyToMessageId != 0L && item.replyToPreview.isBlank()) {
                            val target = byId[item.replyToMessageId]
                            if (target != null) {
                                val snippet = target.text.ifBlank { target.mediaCaption }.replace('\n', ' ').trim().take(40)
                                "${target.senderName}: $snippet"
                            } else {
                                unresolvedReplyIds.add(item.replyToMessageId)
                                item.replyToPreview
                            }
                        } else {
                            item.replyToPreview
                        }
                    item.copy(mediaFilePath = resolvedPath, replyToPreview = resolvedPreview)
                }
            if (merged == existing) current else current + (chatId to merged)
        }
        unresolvedReplyIds.forEach { replyId ->
            resolveReplyPreviewAsync(chatId, replyId)
        }
    }

    private fun handleFileUpdate(file: TdApi.File) {
        val path = localFilePath(file)
        if (path != null) {
            downloadedFilePaths[file.id] = path
            fileDownloadWaiters.remove(file.id)?.forEach { it.complete(path) }
        } else {
            downloadedFilePaths.remove(file.id)
            if (file.local?.isDownloadingActive == true) return
        }
        customEmojiCache.replaceAll { _, item -> if (item.fileId == file.id) item.copy(filePath = path) else item }
        synchronized(pendingChats) {
            pendingChats.replaceAll { _, chat -> if (chat.avatarFileId == file.id) chat.copy(avatarPath = path) else chat }
        }
        _chats.update { chats ->
            if (chats.none { it.avatarFileId == file.id && it.avatarPath != path }) {
                chats
            } else {
                chats.map { if (it.avatarFileId == file.id) it.copy(avatarPath = path) else it }
            }
        }
        _messages.update { current ->
            val hasMatch = current.values.any { list -> list.any { it.mediaFileId == file.id && it.mediaFilePath != path } }
            if (!hasMatch) {
                current
            } else {
                current.mapValues { (_, messages) ->
                    if (messages.none { it.mediaFileId == file.id && it.mediaFilePath != path }) {
                        messages
                    } else {
                        messages.map { if (it.mediaFileId == file.id) it.copy(mediaFilePath = path) else it }
                    }
                }
            }
        }
        if (path != null && com.teleflip.app.storage.StorageManager.isSaveToDownloadsEnabled(context)) {
            scope.launch(Dispatchers.IO) {
                try {
                    val source = File(path)
                    val destination = File(com.teleflip.app.storage.StorageManager.getDownloadDirectory(context), source.name)
                    if (!destination.exists()) source.copyTo(destination)
                } catch (e: Exception) {
                    Log.w(tag, "Could not save downloaded media: ${e.message}")
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

    data class PaidMediaQuote(val token: String, val chatId: Long, val messageId: Long, val stars: Long, val formId: Long, val createdAt: Long)

    private val paidMediaQuotes = java.util.concurrent.ConcurrentHashMap<String, PaidMediaQuote>()

    fun quotePaidMedia(
        chatId: Long,
        messageId: Long,
        callback: (PaidMediaQuote?, String?) -> Unit,
    ) {
        val client =
            tdClient ?: run {
                callback(null, "TDLib is not initialized")
                return
            }
        client.send(TdApi.GetPaymentForm(TdApi.InputInvoiceMessage(chatId, messageId), null)) { result ->
            val form = result as? TdApi.PaymentForm
            val stars = (form?.type as? TdApi.PaymentFormTypeStars)?.starCount
            if (form != null && stars != null && stars > 0) {
                val now = SystemClock.elapsedRealtime()
                paidMediaQuotes.entries.removeAll { now - it.value.createdAt > 300_000 }
                val quote = PaidMediaQuote(java.util.UUID.randomUUID().toString(), chatId, messageId, stars, form.id, now)
                paidMediaQuotes[quote.token] = quote
                callback(quote, null)
            } else {
                callback(null, (result as? TdApi.Error)?.message ?: "This media has no supported Stars payment form")
            }
        }
    }

    fun purchasePaidMedia(
        quote: PaidMediaQuote,
        callback: (Boolean, String?) -> Unit,
    ) {
        val client =
            tdClient ?: run {
                callback(false, "TDLib is not initialized")
                return
            }
        if (!paidMediaQuotes.remove(quote.token, quote) || SystemClock.elapsedRealtime() - quote.createdAt > 300_000) {
            callback(false, "Payment confirmation expired. Please check the price again.")
            return
        }
        paidMediaQuotes.entries.removeAll { it.value.chatId == quote.chatId && it.value.messageId == quote.messageId }
        // Consuming the form prevents repeated taps from sending the same payment twice.
        client.send(TdApi.SendPaymentForm(TdApi.InputInvoiceMessage(quote.chatId, quote.messageId), quote.formId, "", "", null, 0L)) { result ->
            if (result is TdApi.PaymentResult && result.success) {
                unlockPaidMedia(quote.chatId, quote.messageId)
                callback(true, null)
            } else {
                callback(false, (result as? TdApi.Error)?.message ?: "Payment was not completed. Check your Stars balance and try again.")
            }
        }
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
        client.send(TdApi.GetMessage(chatId, messageId)) { result ->
            val content = (result as? TdApi.Message)?.content as? TdApi.MessagePaidMedia
            val available = content != null && !extractIsPaidMediaLocked(content)
            if (content != null) handleMessageContentUpdated(chatId, messageId, content)
            callback?.invoke(available)
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
            TdApi.MessageAnimation.CONSTRUCTOR -> (content as TdApi.MessageAnimation).caption.text
            TdApi.MessageAudio.CONSTRUCTOR -> (content as TdApi.MessageAudio).caption.text
            TdApi.MessagePaidMedia.CONSTRUCTOR -> (content as TdApi.MessagePaidMedia).caption.text
            else -> ""
        }
    }

    private fun extractMediaFileInfo(content: TdApi.MessageContent?): Pair<Int, String?> {
        if (content == null) return Pair(0, null)
        return when (content.constructor) {
            TdApi.MessagePhoto.CONSTRUCTOR -> {
                val photo = content as TdApi.MessagePhoto
                val size = photo.photo.sizes.maxByOrNull { it.width.toLong() * it.height }
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
            TdApi.MessageVideoNote.CONSTRUCTOR -> {
                val videoNote = (content as TdApi.MessageVideoNote).videoNote
                resolveFile(videoNote.video)
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

    private val downloadedFilePaths = java.util.concurrent.ConcurrentHashMap<Int, String>()

    private fun localFilePath(file: TdApi.File): String? =
        file.local?.let { local ->
            local.path.takeIf { local.isDownloadingCompleted && it.isNotBlank() && File(it).isFile }
        }

    private fun resolveFile(f: TdApi.File?): Pair<Int, String?> {
        if (f == null) return Pair(0, null)
        val path = localFilePath(f) ?: downloadedFilePaths[f.id]?.takeIf { File(it).isFile }
        if (path != null) return Pair(f.id, path)
        val fileBytes = if (f.expectedSize > 0) f.expectedSize else f.size
        if (f.local?.canBeDownloaded == true && !f.local.isDownloadingActive &&
            fileBytes <= 15L * 1024L * 1024L &&
            com.teleflip.app.storage.StorageManager.shouldAutoDownloadMedia(context)
        ) {
            requestFileDownload(f.id)
        }
        return Pair(f.id, downloadedFilePaths[f.id]?.takeIf { File(it).isFile })
    }

    fun deleteMessage(
        chatId: Long,
        messageId: Long,
    ) {
        scope.launch {
            try {
                sendQuery(TdApi.DeleteMessages(chatId, longArrayOf(messageId), true))
                _messages.update { it + (chatId to it[chatId].orEmpty().filter { message -> message.id != messageId }) }
            } catch (e: Exception) {
                reportOperationError(e.message ?: "Could not delete message")
            }
        }
    }

    private fun extractMediaType(content: TdApi.MessageContent?): MediaType {
        if (content == null) return MediaType.NONE
        return when (content.constructor) {
            TdApi.MessageContact.CONSTRUCTOR -> MediaType.CONTACT
            TdApi.MessageVoiceNote.CONSTRUCTOR -> MediaType.VOICE
            TdApi.MessageAudio.CONSTRUCTOR -> MediaType.AUDIO
            TdApi.MessageLocation.CONSTRUCTOR -> MediaType.LOCATION
            TdApi.MessagePhoto.CONSTRUCTOR -> MediaType.PHOTO
            TdApi.MessageDocument.CONSTRUCTOR -> MediaType.DOCUMENT
            TdApi.MessageSticker.CONSTRUCTOR -> MediaType.STICKER
            TdApi.MessageVideo.CONSTRUCTOR, TdApi.MessageVideoNote.CONSTRUCTOR -> MediaType.VIDEO
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
            TdApi.MessageContact.CONSTRUCTOR -> (content as TdApi.MessageContact).contact.let { "Contact: ${it.firstName} ${it.lastName}\n${it.phoneNumber}".trim() }
            TdApi.MessageVoiceNote.CONSTRUCTOR -> "🎤 [Voice Note]"
            TdApi.MessageLocation.CONSTRUCTOR -> "📍 [Location]"
            TdApi.MessageAudio.CONSTRUCTOR -> "🎵 [Audio]"
            TdApi.MessageVideo.CONSTRUCTOR -> "📹 [Video] " + (content as TdApi.MessageVideo).caption.text
            TdApi.MessageVideoNote.CONSTRUCTOR -> "📹 [Video]"
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
            TdApi.MessageCall.CONSTRUCTOR -> {
                val call = content as TdApi.MessageCall
                val icon = if (call.isVideo) "📹" else "📞"
                val dur = if (call.duration > 0) " (${call.duration}s)" else ""
                "$icon [Call$dur]"
            }
            TdApi.MessagePinMessage.CONSTRUCTOR -> "📌 [Pinned a message]"
            TdApi.MessageChatAddMembers.CONSTRUCTOR -> "👥 [Added member(s)]"
            TdApi.MessageChatJoinByLink.CONSTRUCTOR -> "👥 [Joined via invite link]"
            TdApi.MessageChatJoinByRequest.CONSTRUCTOR -> "👥 [Joined the chat]"
            TdApi.MessageChatDeleteMember.CONSTRUCTOR -> "👤 [Left the chat]"
            TdApi.MessageChatChangeTitle.CONSTRUCTOR -> "✏️ [Changed title to \"${(content as TdApi.MessageChatChangeTitle).title}\"]"
            TdApi.MessageChatChangePhoto.CONSTRUCTOR -> "🖼️ [Changed chat photo]"
            TdApi.MessageChatDeletePhoto.CONSTRUCTOR -> "🖼️ [Removed chat photo]"
            TdApi.MessageScreenshotTaken.CONSTRUCTOR -> "📸 [Took a screenshot]"
            TdApi.MessageAnimatedEmoji.CONSTRUCTOR -> (content as TdApi.MessageAnimatedEmoji).emoji.ifBlank { "✨ [Emoji]" }
            TdApi.MessageDice.CONSTRUCTOR -> {
                val dice = content as TdApi.MessageDice
                val valStr = if (dice.value > 0) " (${dice.value})" else ""
                "${dice.emoji.ifBlank { "🎲" }}$valStr"
            }
            TdApi.MessageForumTopicCreated.CONSTRUCTOR -> "🧵 [Topic created: ${(content as TdApi.MessageForumTopicCreated).name}]"
            TdApi.MessageForumTopicEdited.CONSTRUCTOR -> "🧵 [Topic edited]"
            TdApi.MessageForumTopicIsClosedToggled.CONSTRUCTOR -> "🧵 [Topic status updated]"
            TdApi.MessageBasicGroupChatCreate.CONSTRUCTOR -> "👥 [Group created]"
            TdApi.MessageSupergroupChatCreate.CONSTRUCTOR -> "👥 [Chat created]"
            TdApi.MessageCustomServiceAction.CONSTRUCTOR -> (content as TdApi.MessageCustomServiceAction).text.ifBlank { "ℹ️ [Service message]" }
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
        scope.launch {
            try {
                val item = ProxyItem(server = server, port = port, type = type, username = username, password = password, secret = secret)
                val result = sendQuery(TdApi.AddProxy(buildTdApiProxy(item), enableNow, "")) as TdApi.AddedProxy
                ProxyStorage.saveProxy(context, item.copy(id = result.id, isEnabled = enableNow))
                if (enableNow) activeProxyId = result.id
                withContext(Dispatchers.Main) { callback?.invoke(true, null) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { callback?.invoke(false, e.message ?: "Could not add proxy") }
            }
        }
    }

    fun enableProxy(
        proxyId: Int,
        callback: ((Boolean, String?) -> Unit)? = null,
    ) {
        scope.launch {
            try {
                val item = ProxyStorage.getProxies(context).find { it.id == proxyId } ?: error("Proxy not found")
                val added = sendQuery(TdApi.AddProxy(buildTdApiProxy(item), true, "")) as TdApi.AddedProxy
                ProxyStorage.deleteProxy(context, proxyId)
                ProxyStorage.saveProxy(context, item.copy(id = added.id, isEnabled = true))
                activeProxyId = added.id
                withContext(Dispatchers.Main) { callback?.invoke(true, null) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { callback?.invoke(false, e.message ?: "Could not enable proxy") }
            }
        }
    }

    fun disableProxy(callback: ((Boolean, String?) -> Unit)? = null) {
        scope.launch {
            try {
                sendQuery(TdApi.DisableProxy())
                ProxyStorage.disableAllProxies(context)
                activeProxyId = 0
                withContext(Dispatchers.Main) { callback?.invoke(true, null) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { callback?.invoke(false, e.message ?: "Could not disable proxy") }
            }
        }
    }

    fun deleteProxy(
        proxyId: Int,
        callback: ((Boolean, String?) -> Unit)? = null,
    ) {
        scope.launch {
            try {
                if (activeProxyId == proxyId) sendQuery(TdApi.DisableProxy())
                sendQuery(TdApi.RemoveProxy(proxyId))
                ProxyStorage.deleteProxy(context, proxyId)
                if (activeProxyId == proxyId) activeProxyId = 0
                withContext(Dispatchers.Main) { callback?.invoke(true, null) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { callback?.invoke(false, e.message ?: "Could not delete proxy") }
            }
        }
    }
}
