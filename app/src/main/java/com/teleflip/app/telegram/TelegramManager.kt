package com.teleflip.app.telegram

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.teleflip.app.model.ChatModel
import com.teleflip.app.model.ChatType
import com.teleflip.app.model.MediaType
import com.teleflip.app.model.MessageModel
import com.teleflip.app.model.MessageStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    data class WaitPassword(val hint: String = "", val errorMessage: String? = null) : AuthState()
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

    private var tdClient: Client? = null
    var isDemoMode = false
        private set

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
        if (isDemoMode) return
        try {
            _connectionState.value = "Connecting..."
            com.teleflip.app.report.AdbReporter.reportInfo(tag, "Creating TDLib Client...")
            tdClient = Client.create(this, null, null)
        } catch (e: Exception) {
            com.teleflip.app.report.AdbReporter.reportError(tag, "Client creation error: ${e.message}", e)
            _connectionState.value = "Init error"
        }
    }

    fun enableDemoMode() {
        isDemoMode = true
        _connectionState.value = "Demo (Offline)"
        _authState.value = AuthState.Ready

        val demoChats = listOf(
            ChatModel(1L, "Telegram News", "Welcome to TeleFlip on your flip phone!", "12:00", 1, ChatType.CHANNEL, "TN", true),
            ChatModel(2L, "Alice Smith", "Hey, does your flip phone have Telegram now?", "12:35", 2, ChatType.DIRECT, "AS"),
            ChatModel(3L, "Family Group", "Dinner tonight at 7 PM", "11:15", 0, ChatType.GROUP, "FG"),
            ChatModel(4L, "Tech & Gadgets", "Physical keypads are having a huge comeback!", "Yesterday", 0, ChatType.GROUP, "TG"),
            ChatModel(5L, "Bob Jones", "Sent photo of the classic phone", "Oct 14", 0, ChatType.DIRECT, "BJ")
        )
        _chats.value = demoChats

        val demoMessages = mutableMapOf<Long, List<MessageModel>>()
        demoMessages[1L] = listOf(
            MessageModel(101L, 1L, "Telegram News", "TeleFlip is fully configured for D-Pad and T9 keypad navigation.", "12:00", false, MessageStatus.READ)
        )
        demoMessages[2L] = listOf(
            MessageModel(201L, 2L, "Alice Smith", "Hey, does your flip phone have Telegram now?", "12:35", false, MessageStatus.READ),
            MessageModel(202L, 2L, "Alice Smith", "How is typing with the 9-key keypad?", "12:36", false, MessageStatus.READ)
        )
        demoMessages[3L] = listOf(
            MessageModel(301L, 3L, "Mom", "Don't forget family dinner tonight at 7!", "11:00", false, MessageStatus.READ),
            MessageModel(302L, 3L, "You", "Sounds good, see you all there!", "11:15", true, MessageStatus.READ)
        )
        demoMessages[5L] = listOf(
            MessageModel(501L, 5L, "Bob Jones", "Check out this phone!", "Oct 14", false, MessageStatus.READ, MediaType.PHOTO, "flip_phone.jpg")
        )
        _messages.value = demoMessages
    }

    var isAuthQueryInProgress = false
        private set

    fun sendPhoneNumber(phone: String) {
        if (isDemoMode) {
            _authState.value = AuthState.WaitCode("Demo mode: enter any 5 digits")
            return
        }
        if (isAuthQueryInProgress) {
            com.teleflip.app.report.AdbReporter.reportInfo(tag, "sendPhoneNumber ignored: authorization query already in flight")
            return
        }
        isAuthQueryInProgress = true
        val cleanPhone = org.telegram.PhoneFormat.PhoneFormat.stripExceptNumbers(phone, true) ?: phone
        val normalized = if (!cleanPhone.startsWith("+")) "+$cleanPhone" else cleanPhone
        com.teleflip.app.report.AdbReporter.reportInfo(tag, "Submitting phone number: $normalized")
        val settings = TdApi.PhoneNumberAuthenticationSettings().apply {
            allowFlashCall = false
            allowMissedCall = false
            isCurrentPhoneNumber = true
            allowSmsRetrieverApi = false
        }
        tdClient?.send(TdApi.SetAuthenticationPhoneNumber(normalized, settings), this)
    }

    fun checkCode(code: String) {
        if (isDemoMode) {
            _authState.value = AuthState.Ready
            return
        }
        if (isAuthQueryInProgress) {
            com.teleflip.app.report.AdbReporter.reportInfo(tag, "checkCode ignored: authorization query already in flight")
            return
        }
        isAuthQueryInProgress = true
        tdClient?.send(TdApi.CheckAuthenticationCode(code), this)
    }

    fun checkPassword(password: String) {
        if (isDemoMode) {
            _authState.value = AuthState.Ready
            return
        }
        if (isAuthQueryInProgress) {
            com.teleflip.app.report.AdbReporter.reportInfo(tag, "checkPassword ignored: authorization query already in flight")
            return
        }
        isAuthQueryInProgress = true
        tdClient?.send(TdApi.CheckAuthenticationPassword(password), this)
    }

    fun sendMessage(chatId: Long, text: String) {
        if (text.isBlank()) return

        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val newMsg = MessageModel(
            id = System.currentTimeMillis(),
            chatId = chatId,
            senderName = "You",
            text = text,
            time = timeStr,
            isOutbound = true,
            status = MessageStatus.SENT
        )

        appendMessage(chatId, newMsg)

        if (isDemoMode) {
            scope.launch {
                delay(2000)
                val replyTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                val replyMsg = MessageModel(
                    id = System.currentTimeMillis(),
                    chatId = chatId,
                    senderName = _chats.value.find { it.id == chatId }?.title ?: "Friend",
                    text = "Got your message: \"$text\"!",
                    time = replyTime,
                    isOutbound = false,
                    status = MessageStatus.READ
                )
                appendMessage(chatId, replyMsg)
            }
            return
        }

        // Live TDLib send text message
        val content = TdApi.InputMessageText(
            TdApi.FormattedText(text, emptyArray()),
            null,
            true
        )
        val sendReq = TdApi.SendMessage().apply {
            this.chatId = chatId
            this.inputMessageContent = content
        }
        tdClient?.send(sendReq, this)
    }

    fun sendVoiceNote(chatId: Long, filePath: String, durationSeconds: Int) {
        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val newMsg = MessageModel(
            id = System.currentTimeMillis(),
            chatId = chatId,
            senderName = "You",
            text = "🎤 Voice Message (${durationSeconds}s)",
            time = timeStr,
            isOutbound = true,
            status = MessageStatus.SENT,
            mediaType = MediaType.VOICE,
            mediaFilePath = filePath,
            durationSeconds = durationSeconds
        )

        appendMessage(chatId, newMsg)

        if (isDemoMode) {
            scope.launch {
                delay(2500)
                val replyTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                val replyMsg = MessageModel(
                    id = System.currentTimeMillis(),
                    chatId = chatId,
                    senderName = _chats.value.find { it.id == chatId }?.title ?: "Friend",
                    text = "Heard your ${durationSeconds}s voice recording! The flip phone mic sounds great.",
                    time = replyTime,
                    isOutbound = false,
                    status = MessageStatus.READ
                )
                appendMessage(chatId, replyMsg)
            }
            return
        }

        // Live TDLib Send Voice Note
        try {
            val inputVoice = TdApi.InputVoiceNote().apply {
                this.voiceNote = TdApi.InputFileLocal(filePath)
                this.duration = durationSeconds
                this.waveform = byteArrayOf()
            }
            val voiceContent = TdApi.InputMessageVoiceNote().apply {
                this.voiceNote = inputVoice
            }
            val sendReq = TdApi.SendMessage().apply {
                this.chatId = chatId
                this.inputMessageContent = voiceContent
            }
            tdClient?.send(sendReq, this)
        } catch (e: Exception) {
            Log.e(tag, "Failed to send voice note via TDLib: ${e.message}")
        }
    }

    fun sendLocation(chatId: Long, latitude: Double, longitude: Double, accuracy: Float) {
        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val latStr = String.format(Locale.US, "%.6f", latitude)
        val lngStr = String.format(Locale.US, "%.6f", longitude)
        val gmapsLink = "https://maps.google.com/?q=$latStr,$lngStr"
        val osmLink = "https://www.openstreetmap.org/?mlat=$latStr&mlon=$lngStr"

        val bodyText = "📍 Current GPS Location\nGoogle Maps: $gmapsLink\nOSM: $osmLink"

        val newMsg = MessageModel(
            id = System.currentTimeMillis(),
            chatId = chatId,
            senderName = "You",
            text = bodyText,
            time = timeStr,
            isOutbound = true,
            status = MessageStatus.SENT,
            mediaType = MediaType.LOCATION,
            latitude = latitude,
            longitude = longitude
        )

        appendMessage(chatId, newMsg)

        if (isDemoMode) {
            scope.launch {
                delay(2000)
                val replyTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                val replyMsg = MessageModel(
                    id = System.currentTimeMillis(),
                    chatId = chatId,
                    senderName = _chats.value.find { it.id == chatId }?.title ?: "Friend",
                    text = "Received your location! I can open it directly in Google Maps or Organic Maps.",
                    time = replyTime,
                    isOutbound = false,
                    status = MessageStatus.READ
                )
                appendMessage(chatId, replyMsg)
            }
            return
        }

        // Live TDLib Send Location
        try {
            val locContent = TdApi.InputMessageLocation().apply {
                this.location = TdApi.Location(latitude, longitude, 0.0)
            }
            val sendReq = TdApi.SendMessage().apply {
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

    fun updateMessageStatus(chatId: Long, messageId: Long, newStatus: MessageStatus) {
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

    fun resendMessage(chatId: Long, messageId: Long) {
        val currentMap = _messages.value.toMutableMap()
        val chatMsgs = (currentMap[chatId] ?: emptyList()).toMutableList()
        val msg = chatMsgs.find { it.id == messageId } ?: return

        updateMessageStatus(chatId, messageId, MessageStatus.SENDING)

        if (!isDemoMode && (_connectionState.value == "Offline" || _connectionState.value == "Waiting for network")) {
            updateMessageStatus(chatId, messageId, MessageStatus.FAILED)
            return
        }

        if (isDemoMode) {
            scope.launch {
                delay(1000)
                updateMessageStatus(chatId, messageId, MessageStatus.SENT)
                if (msg.text.isNotBlank()) {
                    val replyTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                    val replyMsg = MessageModel(
                        id = System.currentTimeMillis(),
                        chatId = chatId,
                        senderName = _chats.value.find { it.id == chatId }?.title ?: "Friend",
                        text = "Got your resent message: \"${msg.text}\"!",
                        time = replyTime,
                        isOutbound = false,
                        status = MessageStatus.READ
                    )
                    appendMessage(chatId, replyMsg)
                }
            }
            return
        }

        // Live TDLib resend message
        try {
            val resendReq = TdApi.ResendMessages().apply {
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

    private fun appendMessage(chatId: Long, message: MessageModel) {
        val currentMap = _messages.value.toMutableMap()
        val chatMsgs = (currentMap[chatId] ?: emptyList()).toMutableList()
        chatMsgs.add(message)
        currentMap[chatId] = chatMsgs
        _messages.value = currentMap

        val updatedChats = _chats.value.map {
            if (it.id == chatId) it.copy(lastMessage = message.text, time = message.time) else it
        }
        _chats.value = updatedChats
    }

    fun loadChats(limit: Int = 30) {
        if (isDemoMode) return
        tdClient?.send(TdApi.GetChats(TdApi.ChatListMain(), limit), this)
    }

    fun loadMoreChats(step: Int = 20) {
        if (isDemoMode) return
        val currentCount = _chats.value.size
        tdClient?.send(TdApi.GetChats(TdApi.ChatListMain(), currentCount + step), this)
    }

    fun loadMessages(chatId: Long, fromMessageId: Long = 0, limit: Int = 30) {
        if (isDemoMode) return
        tdClient?.send(TdApi.GetChatHistory(chatId, fromMessageId, 0, limit, false), this)
    }

    fun logOut() {
        if (isDemoMode) {
            isDemoMode = false
            _authState.value = AuthState.WaitPhoneNumber()
            _chats.value = emptyList()
            _messages.value = emptyMap()
            return
        }
        tdClient?.send(TdApi.LogOut(), this)
    }

    override fun onResult(obj: TdApi.Object?) {
        if (obj == null) return
        if (isDemoMode) return // Keep user in demo mode without TDLib interference

        when (obj.constructor) {
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
                for (id in chatIds) {
                    tdClient?.send(TdApi.GetChat(id), this)
                }
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
            TdApi.Error.CONSTRUCTOR -> {
                isAuthQueryInProgress = false
                val err = obj as TdApi.Error
                com.teleflip.app.report.AdbReporter.reportError(tag, "TDLib Server Error [Code: ${err.code}]: ${err.message}")
                when (val curr = _authState.value) {
                    is AuthState.WaitPhoneNumber -> _authState.value = curr.copy(errorMessage = err.message)
                    is AuthState.WaitCode -> _authState.value = curr.copy(errorMessage = err.message)
                    is AuthState.WaitPassword -> _authState.value = curr.copy(errorMessage = err.message)
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
        isAuthQueryInProgress = false
        com.teleflip.app.report.AdbReporter.reportInfo(tag, "Auth state updated: ${state.javaClass.simpleName}")
        when (state.constructor) {
            TdApi.AuthorizationStateWaitTdlibParameters.CONSTRUCTOR -> {
                if (this@TelegramManager.apiId == 0 || this@TelegramManager.apiHash.isBlank()) {
                    _authState.value = AuthState.WaitPhoneNumber(errorMessage = "Please configure your Telegram API ID & Hash in Settings (Config)")
                    return
                }
                val dbDir = File(context.filesDir, "tdlib_${this@TelegramManager.apiId}").absolutePath
                val params = TdApi.SetTdlibParameters().apply {
                    databaseDirectory = dbDir
                    useMessageDatabase = true
                    useSecretChats = false
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
                val hint = (state as TdApi.AuthorizationStateWaitPassword).passwordHint
                _authState.value = AuthState.WaitPassword(hint)
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
        val label = when (state.constructor) {
            TdApi.ConnectionStateConnecting.CONSTRUCTOR -> "Connecting..."
            TdApi.ConnectionStateConnectingToProxy.CONSTRUCTOR -> "Proxy..."
            TdApi.ConnectionStateUpdating.CONSTRUCTOR -> "Updating..."
            TdApi.ConnectionStateReady.CONSTRUCTOR -> "Online"
            else -> "Waiting for network"
        }
        _connectionState.value = label
        com.teleflip.app.report.AdbReporter.reportInfo(tag, "Connection state: $label")
    }

    private val pendingChats = mutableMapOf<Long, ChatModel>()
    private var batchJob: Job? = null

    private fun addOrUpdateChat(chat: TdApi.Chat) {
        val title = chat.title.ifBlank { "Chat #${chat.id}" }
        val lastMsg = chat.lastMessage
        val lastMsgText = extractMessageText(lastMsg?.content)
        val timeStr = if (lastMsg != null) formatTime(lastMsg.date.toLong() * 1000) else ""

        val type = when (chat.type.constructor) {
            TdApi.ChatTypePrivate.CONSTRUCTOR -> ChatType.DIRECT
            TdApi.ChatTypeBasicGroup.CONSTRUCTOR, TdApi.ChatTypeSupergroup.CONSTRUCTOR -> ChatType.GROUP
            else -> ChatType.CHANNEL
        }

        val letters = title.split(" ")
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

        val newModel = ChatModel(
            id = chat.id,
            title = title,
            lastMessage = lastMsgText,
            time = timeStr,
            unreadCount = chat.unreadCount,
            chatType = type,
            avatarLetters = letters,
            avatarPath = avatarPath,
            avatarFileId = avatarFileId
        )

        synchronized(pendingChats) {
            pendingChats[chat.id] = newModel
        }

        if (batchJob?.isActive != true) {
            batchJob = scope.launch {
                delay(120)
                val currentList = _chats.value.toMutableList()
                val updates: List<ChatModel>
                synchronized(pendingChats) {
                    updates = pendingChats.values.toList()
                    pendingChats.clear()
                }
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

    private fun updateChatLastMessage(chatId: Long, msg: TdApi.Message?) {
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

    private fun handleIncomingMessage(msg: TdApi.Message) {
        val mediaType = extractMediaType(msg.content)
        val mediaInfo = extractMediaFileInfo(msg.content)
        val model = MessageModel(
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
            mediaFileId = mediaInfo.first
        )

        appendMessage(msg.chatId, model)
    }

    private fun handleMessagesList(msgs: Array<TdApi.Message>) {
        if (msgs.isEmpty()) return
        val chatId = msgs.first().chatId
        val list = msgs.reversed().map { msg ->
            val mediaInfo = extractMediaFileInfo(msg.content)
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
                mediaFileId = mediaInfo.first
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
        val currentChats = _chats.value.map { chat ->
            if (chat.avatarFileId == file.id && chat.avatarPath != file.local.path) {
                chatUpdated = true
                chat.copy(avatarPath = file.local.path)
            } else chat
        }
        if (chatUpdated) {
            _chats.value = currentChats
        }

        // 2. Check if matches any message media
        val currentMsgs = _messages.value.toMutableMap()
        var msgUpdated = false
        for ((cId, list) in currentMsgs) {
            val updatedList = list.map { msg ->
                if (msg.mediaFileId == file.id && msg.mediaFilePath != file.local.path) {
                    msgUpdated = true
                    msg.copy(mediaFilePath = file.local.path)
                } else msg
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
            TdApi.MessageDocument.CONSTRUCTOR -> (content as TdApi.MessageDocument).caption.text.ifBlank { (content as TdApi.MessageDocument).document.fileName }
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
                } else Pair(0, null)
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

    fun deleteMessage(chatId: Long, messageId: Long) {
        if (isDemoMode) {
            val currentMap = _messages.value.toMutableMap()
            val list = currentMap[chatId]?.filter { it.id != messageId } ?: emptyList()
            currentMap[chatId] = list
            _messages.value = currentMap
            return
        }
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
            TdApi.MessageVideo.CONSTRUCTOR -> "📹 [Video]"
            TdApi.MessageDocument.CONSTRUCTOR -> "📄 " + (content as TdApi.MessageDocument).document.fileName
            TdApi.MessageSticker.CONSTRUCTOR -> (content as TdApi.MessageSticker).sticker.emoji + " [Sticker]"
            else -> "[Message]"
        }
    }

    private fun formatTime(millis: Long): String {
        val df = SimpleDateFormat("HH:mm", Locale.getDefault())
        return df.format(Date(millis))
    }
}
