package com.teleflip.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.util.Log
import java.io.File
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.teleflip.app.input.KeypadInputEngine
import com.teleflip.app.location.LocationHelper
import com.teleflip.app.media.VoicePlayer
import com.teleflip.app.media.VoiceRecorder
import com.teleflip.app.model.ChatModel
import com.teleflip.app.model.ChatType
import com.teleflip.app.model.MediaType
import com.teleflip.app.model.MessageModel
import com.teleflip.app.model.MessageStatus
import com.teleflip.app.telegram.AuthState
import com.teleflip.app.telegram.TelegramManager
import com.teleflip.app.ui.ChatListAdapter
import com.teleflip.app.ui.MenuOption
import com.teleflip.app.ui.MessageAdapter
import com.teleflip.app.ui.OptionsMenuDialog
import com.teleflip.app.ui.SoftkeyManager
import com.teleflip.app.ui.SoftkeyUtils
import com.teleflip.app.ui.SymbolsDialog
import com.teleflip.app.ui.loadCircularAvatarOrFallback
import java.util.Locale
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

enum class Screen {
    LOGIN, CHAT_LIST, CONVERSATION, SETTINGS
}

class MainActivity : AppCompatActivity() {

    private lateinit var mainContainer: FrameLayout
    private lateinit var softkeyManager: SoftkeyManager
    private lateinit var telegramManager: TelegramManager
    private lateinit var voiceRecorder: VoiceRecorder
    private lateinit var voicePlayer: VoicePlayer
    private lateinit var locationHelper: LocationHelper

    private var currentScreen: Screen = Screen.CHAT_LIST
    private var activeChat: ChatModel? = null
    private var pendingTargetMessageId: Long? = null

    // Chat List State
    private var currentChatTypeFilter: ChatType? = null
    private var chatListAdapter: ChatListAdapter? = null
    private var rvChats: RecyclerView? = null
    private var tabDebounceJob: kotlinx.coroutines.Job? = null

    // Conversation State
    private var messageAdapter: MessageAdapter? = null
    private var rvMessages: RecyclerView? = null
    private var etComposeInput: EditText? = null
    private var composeInputEngine: KeypadInputEngine? = null

    // Voice Recording State
    private var layoutVoiceRecording: LinearLayout? = null
    private var layoutCompose: LinearLayout? = null
    private var tvRecordTimer: TextView? = null

    // Login State
    private var etLoginInput: EditText? = null
    private var loginInputEngine: KeypadInputEngine? = null

    companion object {
        private const val REQ_AUDIO_PERM = 1001
        private const val REQ_LOC_PERM = 1002
        private const val REQ_PHONE_PERM = 1003

        internal fun cleanAndFormatPhoneNumber(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val clean = org.telegram.PhoneFormat.PhoneFormat.stripExceptNumbers(raw, true)
            if (clean.isNullOrBlank()) return null
            return if (clean.startsWith("+")) clean else "+$clean"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        com.teleflip.app.report.AdbReporter.init(this)

        val phonePerms = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            phonePerms.add(Manifest.permission.READ_PHONE_STATE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_NUMBERS) != PackageManager.PERMISSION_GRANTED) {
            phonePerms.add(Manifest.permission.READ_PHONE_NUMBERS)
        }
        if (phonePerms.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, phonePerms.toTypedArray(), REQ_PHONE_PERM)
        }

        mainContainer = findViewById(R.id.main_container)

        val softkeyBar = findViewById<View>(R.id.softkey_bar)
        val tvLsk = findViewById<TextView>(R.id.tv_softkey_left)
        val tvCenter = findViewById<TextView>(R.id.tv_softkey_center)
        val tvRsk = findViewById<TextView>(R.id.tv_softkey_right)
        softkeyManager = SoftkeyManager(softkeyBar, tvLsk, tvCenter, tvRsk)

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (voiceRecorder.isRecording()) {
                    activeChat?.let { cancelVoiceRecordingUI(it) }
                    return
                }
                when (currentScreen) {
                    Screen.CONVERSATION -> showChatListScreen()
                    Screen.SETTINGS -> {
                        if (telegramManager.authState.value is AuthState.Ready) {
                            showChatListScreen()
                        } else {
                            showLoginScreen(telegramManager.authState.value)
                        }
                    }
                    Screen.LOGIN, Screen.CHAT_LIST -> finish()
                }
            }
        })

        telegramManager = TelegramManager(this)
        voiceRecorder = VoiceRecorder(this)
        voicePlayer = VoicePlayer(this)
        locationHelper = LocationHelper(this)

        observeTelegramState()

        telegramManager.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceRecorder.cancelRecording()
        voicePlayer.stop()
    }

    private fun observeTelegramState() {
        lifecycleScope.launch {
            telegramManager.authState.collectLatest { state ->
                when (state) {
                    is AuthState.Initial, is AuthState.WaitParameters -> {}
                    is AuthState.WaitPhoneNumber, is AuthState.WaitCode, is AuthState.WaitPassword, is AuthState.WaitPasswordRecoveryCode, is AuthState.Error -> {
                        if (currentScreen != Screen.SETTINGS && !telegramManager.isDemoMode) {
                            showLoginScreen(state)
                        }
                    }
                    is AuthState.Ready -> {
                        if (currentScreen == Screen.LOGIN || mainContainer.childCount == 0) {
                            showChatListScreen()
                        }
                    }
                }
            }
        }

        lifecycleScope.launch {
            telegramManager.connectionState.collectLatest { status ->
val tvConnectionStatus = findViewById<TextView>(R.id.tv_connection_status)
                tvConnectionStatus?.text = status
                tvConnectionStatus?.announceForAccessibility("Connection status: $status")
                if (currentScreen == Screen.LOGIN) {
                    updateLoginConnectionState(status)
                }
            }
        }

        lifecycleScope.launch {
            telegramManager.isAuthQueryInProgressFlow.collectLatest { inProgress ->
                if (currentScreen == Screen.LOGIN) {
                    updateLoginProgressState(inProgress)
                }
            }
        }

        lifecycleScope.launch {
            telegramManager.chats.collectLatest { chats ->
                if (currentScreen == Screen.CONVERSATION && activeChat != null) {
                    val updatedActive = chats.find { it.id == activeChat?.id }
                    if (updatedActive != null) {
                        activeChat = updatedActive
                        findViewById<TextView>(R.id.tv_conv_star_badge)?.visibility = if (updatedActive.isPremium) View.VISIBLE else View.GONE
                    }
                }
                if (currentScreen == Screen.CHAT_LIST) {
                    val filtered = if (currentChatTypeFilter == null) {
                        chats
                    } else {
                        chats.filter { it.chatType == currentChatTypeFilter }
                    }
                    val wasEmpty = (chatListAdapter?.itemCount ?: 0) == 0
                    chatListAdapter?.submitList(filtered)
                    val emptyView = findViewById<View>(R.id.layout_empty_state)
                    val progressView = findViewById<View>(R.id.progress_chats)

                    if (filtered.isNotEmpty()) {
                        tabDebounceJob?.cancel()
                        progressView?.visibility = View.GONE
                        emptyView?.visibility = View.GONE
                        if (wasEmpty && !isTabViewFocused()) {
                            rvChats?.post {
                                rvChats?.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                            }
                        }
                    } else {
                        if (tabDebounceJob?.isActive != true) {
                            if (chats.isEmpty() && telegramManager.connectionState.value != "Offline") {
                                progressView?.visibility = View.VISIBLE
                                emptyView?.visibility = View.GONE
                            } else {
                                progressView?.visibility = View.GONE
                                emptyView?.visibility = View.VISIBLE
                            }
                        }
                    }
                }
            }
        }

        lifecycleScope.launch {
            telegramManager.messages.collectLatest { map ->
                activeChat?.let { chat ->
                    if (currentScreen == Screen.CONVERSATION) {
                        val list = map[chat.id] ?: emptyList()
                        messageAdapter?.submitList(list)
                        val unread = list.filter { !it.isOutbound }.map { it.id }.toLongArray()
                        if (unread.isNotEmpty()) {
                            telegramManager.markMessagesAsRead(chat.id, unread)
                        }
                        val targetId = pendingTargetMessageId
                        if (targetId != null) {
                            val pos = messageAdapter?.getPositionForMessageId(targetId) ?: -1
                            if (pos != -1) {
                                rvMessages?.scrollToPosition(pos)
                                rvMessages?.post {
                                    rvMessages?.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
                                }
                                pendingTargetMessageId = null
                            } else if (list.isNotEmpty()) {
                                rvMessages?.scrollToPosition(list.size - 1)
                            }
                        } else if (list.isNotEmpty()) {
                            rvMessages?.scrollToPosition(list.size - 1)
                        }
                    }
                }
            }
        }
    }

    private fun commitActiveInputEngines() {
        composeInputEngine?.commitCurrentChar()
        loginInputEngine?.commitCurrentChar()
    }

    // =========================================================================
    // SCREEN 1: CHAT LIST
    // =========================================================================
    private fun showChatListScreen() {
        commitActiveInputEngines()
        currentScreen = Screen.CHAT_LIST
        activeChat?.let { telegramManager.closeChat(it.id) }
        activeChat = null
        voicePlayer.stop()
        mainContainer.removeAllViews()

        val view = LayoutInflater.from(this).inflate(R.layout.view_chat_list, mainContainer, true)
        findViewById<TextView>(R.id.tv_connection_status)?.text = telegramManager.connectionState.value

        rvChats = view.findViewById(R.id.rv_chats)
        rvChats?.layoutManager = LinearLayoutManager(this)
        chatListAdapter = ChatListAdapter { chat ->
            openConversation(chat)
        }
        rvChats?.adapter = chatListAdapter

        // Lazy loading / pagination on scroll
        rvChats?.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0) {
                    val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                    val visibleItemCount = lm.childCount
                    val totalItemCount = lm.itemCount
                    val firstVisibleItem = lm.findFirstVisibleItemPosition()
                    if ((visibleItemCount + firstVisibleItem) >= totalItemCount - 2 && totalItemCount >= 10) {
                        telegramManager.loadMoreChats()
                    }
                }
            }
        })

        setupTabs(view)

        softkeyManager.update(
            left = "Options",
            center = "Open",
            right = "Exit",
            onLeft = { showChatListOptionsMenu() },
            onCenter = {
                val focusedView = currentFocus
                val rv = rvChats
                if (focusedView != null && rv != null) {
                    val itemView = rv.findContainingItemView(focusedView)
                    if (itemView != null) {
                        val pos = rv.getChildAdapterPosition(itemView)
                        if (pos != RecyclerView.NO_POSITION) {
                            val item = chatListAdapter?.getItemAt(pos)
                            if (item != null) openConversation(item)
                        }
                    } else if ((chatListAdapter?.itemCount ?: 0) > 0) {
                        val item = chatListAdapter?.getItemAt(0)
                        if (item != null) openConversation(item)
                    }
                }
            },
            onRight = { finish() }
        )

        rvChats?.post {
            if ((chatListAdapter?.itemCount ?: 0) > 0) {
                rvChats?.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
            } else {
                view.findViewById<View>(R.id.tab_all)?.requestFocus()
            }
        }

        applyChatFilterAndDebounce(currentChatTypeFilter)
    }

    private fun isTabViewFocused(): Boolean {
        val focusId = currentFocus?.id ?: return false
        return focusId == R.id.tab_all || focusId == R.id.tab_direct ||
               focusId == R.id.tab_groups || focusId == R.id.tab_channels
    }

    private fun applyChatFilterAndDebounce(filter: ChatType?) {
        tabDebounceJob?.cancel()
        currentChatTypeFilter = filter

        val tabAll = findViewById<TextView>(R.id.tab_all)
        val tabDirect = findViewById<TextView>(R.id.tab_direct)
        val tabGroups = findViewById<TextView>(R.id.tab_groups)
        val tabChannels = findViewById<TextView>(R.id.tab_channels)

        tabAll?.isSelected = (filter == null)
        tabDirect?.isSelected = (filter == ChatType.DIRECT)
        tabGroups?.isSelected = (filter == ChatType.GROUP)
        tabChannels?.isSelected = (filter == ChatType.CHANNEL)

        val allChats = telegramManager.chats.value
        val filtered = if (filter == null) allChats else allChats.filter { it.chatType == filter }
        chatListAdapter?.submitList(filtered)

        val emptyView = findViewById<View>(R.id.layout_empty_state)
        val progressView = findViewById<View>(R.id.progress_chats)

        if (filtered.isNotEmpty()) {
            progressView?.visibility = View.GONE
            emptyView?.visibility = View.GONE
        } else {
            progressView?.visibility = View.VISIBLE
            emptyView?.visibility = View.GONE

            tabDebounceJob = lifecycleScope.launch {
                kotlinx.coroutines.delay(200)
                val reCheckedChats = telegramManager.chats.value
                val reCheckedFiltered = if (currentChatTypeFilter == null) {
                    reCheckedChats
                } else {
                    reCheckedChats.filter { it.chatType == currentChatTypeFilter }
                }
                if (reCheckedFiltered.isEmpty()) {
                    findViewById<View>(R.id.progress_chats)?.visibility = View.GONE
                    findViewById<View>(R.id.layout_empty_state)?.visibility = View.VISIBLE
                } else {
                    findViewById<View>(R.id.progress_chats)?.visibility = View.GONE
                    findViewById<View>(R.id.layout_empty_state)?.visibility = View.GONE
                }
            }
        }
    }

    private fun setupTabs(rootView: View) {
        val tabAll = rootView.findViewById<TextView>(R.id.tab_all)
        val tabDirect = rootView.findViewById<TextView>(R.id.tab_direct)
        val tabGroups = rootView.findViewById<TextView>(R.id.tab_groups)
        val tabChannels = rootView.findViewById<TextView>(R.id.tab_channels)

        fun selectTab(selected: TextView, filter: ChatType?) {
            applyChatFilterAndDebounce(filter)
        }

        tabAll?.setOnClickListener { selectTab(tabAll, null) }
        tabDirect?.setOnClickListener { selectTab(tabDirect, ChatType.DIRECT) }
        tabGroups?.setOnClickListener { selectTab(tabGroups, ChatType.GROUP) }
        tabChannels?.setOnClickListener { selectTab(tabChannels, ChatType.CHANNEL) }

        tabAll?.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) selectTab(tabAll, null) }
        tabDirect?.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) selectTab(tabDirect, ChatType.DIRECT) }
        tabGroups?.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) selectTab(tabGroups, ChatType.GROUP) }
        tabChannels?.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) selectTab(tabChannels, ChatType.CHANNEL) }
    }

    private fun showToast(msg: String, isError: Boolean = false) {
        com.teleflip.app.report.AdbReporter.reportPopup("UI_Popup", msg, isError)
        Toast.makeText(this, msg, if (isError) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    private var activeDialogCount = 0

    fun onDialogShown() {
        activeDialogCount++
        softkeyManager.setSoftkeyBarVisible(false)
    }

    fun onDialogDismissed() {
        activeDialogCount--
        if (activeDialogCount <= 0) {
            activeDialogCount = 0
            softkeyManager.setSoftkeyBarVisible(true)
        }
    }

    private fun showChatListOptionsMenu() {
        commitActiveInputEngines()
        val options = listOf(
            MenuOption(1, "🔍 Search (Chats, Contacts, Messages)") {
                showSearchDialog()
            },
            MenuOption(2, "New Chat / Contact") {
                showToast("Enter username or number to start chat")
            },
            MenuOption(3, "Refresh Chats") {
                telegramManager.loadChats()
                showToast("Refreshing chats...")
            },
            MenuOption(4, "Explore Demo / Offline Chats") {
                telegramManager.enableDemoMode()
                showToast("Demo mode active")
            },
            MenuOption(5, "Diagnostics & Error Log") {
                com.teleflip.app.ui.DiagnosticsDialog(this).show()
            },
            MenuOption(6, "Settings & API") {
                showSettingsScreen()
            },
            MenuOption(7, "Log Out") {
                telegramManager.logOut()
            }
        )
        OptionsMenuDialog(this, "Chat Options", options).show()
    }

    private fun showSearchDialog() {
        com.teleflip.app.ui.SearchDialog(
            context = this,
            telegramManager = telegramManager,
            onChatSelected = { chat ->
                openConversation(chat)
            },
            onMessageSelected = { chatId, messageId ->
                val chat = telegramManager.chats.value.find { it.id == chatId }
                    ?: ChatModel(
                        id = chatId,
                        title = "Chat #$chatId",
                        lastMessage = "",
                        time = "",
                        unreadCount = 0,
                        chatType = ChatType.DIRECT,
                        avatarLetters = "TG"
                    )
                openConversation(chat, targetMessageId = messageId)
            }
        ).show()
    }

    // =========================================================================
    // SCREEN 2: CONVERSATION / CHAT
    // =========================================================================
    private fun openConversation(chat: ChatModel, targetMessageId: Long? = null) {
        commitActiveInputEngines()
        activeChat = chat
        pendingTargetMessageId = targetMessageId
        currentScreen = Screen.CONVERSATION
        telegramManager.openChat(chat.id)
        if (chat.chatType == ChatType.CHANNEL) {
            telegramManager.loadSponsoredMessages(chat.id)
        }
        mainContainer.removeAllViews()

        val view = LayoutInflater.from(this).inflate(R.layout.view_chat_conversation, mainContainer, true)

        view.findViewById<TextView>(R.id.tv_conv_title).text = chat.title
        val tvConvStarBadge = view.findViewById<TextView>(R.id.tv_conv_star_badge)
        tvConvStarBadge?.visibility = if (chat.isPremium) View.VISIBLE else View.GONE
        val subtitle = view.findViewById<TextView>(R.id.tv_conv_subtitle)
        subtitle.text = when (chat.chatType) {
            ChatType.CHANNEL -> "Channel"
            ChatType.GROUP -> "Group"
            ChatType.BOT -> "Bot"
            ChatType.DIRECT -> "online"
        }

        val ivConvAvatar = view.findViewById<ImageView>(R.id.iv_conv_avatar)
        val tvConvAvatar = view.findViewById<TextView>(R.id.tv_conv_avatar)
        ivConvAvatar?.contentDescription = com.teleflip.app.ui.ContentDescriptionFormatter.getAvatarDescription(this, chat.title)
        ivConvAvatar?.loadCircularAvatarOrFallback(chat.avatarPath, chat.avatarLetters, tvConvAvatar)

        rvMessages = view.findViewById(R.id.rv_messages)
        val layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        rvMessages?.layoutManager = layoutManager

        layoutVoiceRecording = view.findViewById(R.id.layout_voice_recording)
        layoutCompose = view.findViewById(R.id.layout_compose)
        tvRecordTimer = view.findViewById(R.id.tv_record_timer)

        messageAdapter = MessageAdapter(
            onMessageFocused = { focusedMsg ->
                updateSoftkeysForFocusedMessage(chat, focusedMsg)
            },
            onMessageAction = { clickedMsg ->
                handleMessageActionClick(clickedMsg)
            }
        )
        rvMessages?.adapter = messageAdapter

        val currentMsgs = telegramManager.messages.value[chat.id] ?: emptyList()
        messageAdapter?.submitList(currentMsgs)
        val unreadInitial = currentMsgs.filter { !it.isOutbound }.map { it.id }.toLongArray()
        if (unreadInitial.isNotEmpty()) {
            telegramManager.markMessagesAsRead(chat.id, unreadInitial)
        }
        if (targetMessageId != null) {
            val pos = messageAdapter?.getPositionForMessageId(targetMessageId) ?: -1
            if (pos != -1) {
                rvMessages?.scrollToPosition(pos)
                rvMessages?.post {
                    rvMessages?.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
                }
                pendingTargetMessageId = null
            } else if (currentMsgs.isNotEmpty()) {
                rvMessages?.scrollToPosition(currentMsgs.size - 1)
            }
        } else if (currentMsgs.isNotEmpty()) {
            rvMessages?.scrollToPosition(currentMsgs.size - 1)
        }

        // Fetch live chat history from TDLib
        telegramManager.loadMessages(chat.id)

        // Lazy load older messages when scrolling up to the top
        rvMessages?.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy < 0) {
                    val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                    val firstVisible = lm.findFirstVisibleItemPosition()
                    if (firstVisible <= 1) {
                        val msgs = telegramManager.messages.value[chat.id] ?: emptyList()
                        if (msgs.isNotEmpty()) {
                            telegramManager.loadMessages(chat.id, fromMessageId = msgs.first().id)
                        }
                    }
                }
            }
        })

        etComposeInput = view.findViewById(R.id.et_message_input)
        val tvMode = view.findViewById<TextView>(R.id.tv_input_mode)
        val btnSend = view.findViewById<Button>(R.id.btn_send)

        composeInputEngine = KeypadInputEngine(
            editText = etComposeInput!!,
            modeIndicator = tvMode,
            timeoutMs = telegramManager.keypadTimeoutMs,
            onSymbolRequested = { showSymbolsDialog { symbol -> composeInputEngine?.insertSymbol(symbol) } }
        )

        val doSend = {
            val text = etComposeInput?.text?.toString()?.trim() ?: ""
            if (text.isNotEmpty()) {
                telegramManager.sendMessage(chat.id, text)
                etComposeInput?.setText("")
            }
        }

        btnSend.setOnClickListener { doSend() }

        etComposeInput?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!s.isNullOrEmpty()) {
                    telegramManager.sendTypingAction(chat.id)
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        etComposeInput?.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && !voiceRecorder.isRecording()) {
                softkeyManager.update(
                    left = "Attach",
                    center = "Send",
                    right = "Back",
                    onLeft = { showAttachMenu(chat) },
                    onCenter = { doSend() },
                    onRight = { showChatListScreen() }
                )
            } else if (!hasFocus) {
                composeInputEngine?.commitCurrentChar()
            }
        }

        // Default softkeys
        softkeyManager.update(
            left = "Attach",
            center = "Send",
            right = "Back",
            onLeft = { showAttachMenu(chat) },
            onCenter = {
                if (etComposeInput?.hasFocus() == true) {
                    doSend()
                } else {
                    etComposeInput?.requestFocus()
                }
            },
            onRight = { showChatListScreen() }
        )

        etComposeInput?.requestFocus()
    }

    private var lastResendTimestamp = 0L

    private fun handleResendMessage(msg: MessageModel) {
        val now = System.currentTimeMillis()
        if (now - lastResendTimestamp < 1000L) {
            return
        }
        lastResendTimestamp = now

        val isOffline = !telegramManager.isDemoMode &&
            (telegramManager.connectionState.value == "Offline" || telegramManager.connectionState.value == "Waiting for network")

        if (isOffline) {
            telegramManager.updateMessageStatus(msg.chatId, msg.id, MessageStatus.FAILED)
            showToast("Offline: Unable to resend message", isError = true)
            return
        }

        showToast("Retrying message delivery...")
        telegramManager.resendMessage(msg.chatId, msg.id)
    }

    private fun updateSoftkeysForFocusedMessage(chat: ChatModel, msg: MessageModel) {
        if (msg.isOutbound && msg.status == MessageStatus.FAILED) {
            softkeyManager.update(
                left = "Options",
                center = "Retry",
                right = "Back",
                onLeft = { showMessageOptionsMenu(msg) },
                onCenter = { handleResendMessage(msg) },
                onRight = { showChatListScreen() }
            )
            return
        }

        when (msg.mediaType) {
            MediaType.VOICE -> {
                val isPlaying = msg.mediaFilePath != null && voicePlayer.isPlayingPath(msg.mediaFilePath)
                if (isPlaying) {
                    val speedText = String.format(Locale.US, "Speed %.1fx", voicePlayer.currentSpeed)
                    softkeyManager.update(
                        left = speedText,
                        center = "Pause",
                        right = "Back",
                        onLeft = {
                            voicePlayer.cyclePlaybackSpeed()
                            messageAdapter?.setPlaybackSpeed(voicePlayer.currentSpeed)
                            updateSoftkeysForFocusedMessage(chat, msg)
                        },
                        onCenter = { handlePlayVoiceMessage(msg) },
                        onRight = { showChatListScreen() }
                    )
                } else {
                    softkeyManager.update(
                        left = "Options",
                        center = "Play",
                        right = "Back",
                        onLeft = { showMessageOptionsMenu(msg) },
                        onCenter = { handlePlayVoiceMessage(msg) },
                        onRight = { showChatListScreen() }
                    )
                }
            }
            MediaType.LOCATION -> {
                softkeyManager.update(
                    left = "Copy Link",
                    center = "Organic Maps",
                    right = "Back",
                    onLeft = { LocationHelper.copyGoogleMapsLink(this, msg.latitude, msg.longitude) },
                    onCenter = { LocationHelper.openInOrganicMaps(this, msg.latitude, msg.longitude) },
                    onRight = { showChatListScreen() }
                )
            }
            else -> {
                softkeyManager.update(
                    left = "Options",
                    center = "Compose",
                    right = "Back",
                    onLeft = { showMessageOptionsMenu(msg) },
                    onCenter = { etComposeInput?.requestFocus() },
                    onRight = { showChatListScreen() }
                )
            }
        }
    }

    private fun handleMessageActionClick(msg: MessageModel) {
        when (msg.mediaType) {
            MediaType.VOICE -> handlePlayVoiceMessage(msg)
            MediaType.LOCATION -> LocationHelper.openInOrganicMaps(this, msg.latitude, msg.longitude)
            else -> showMessageOptionsMenu(msg)
        }
    }

    private fun handlePlayVoiceMessage(msg: MessageModel) {
        val path = msg.mediaFilePath
        if (path.isNullOrBlank()) {
            showToast("Voice note audio file not available offline", true)
            return
        }

        if (voicePlayer.isPlayingPath(path)) {
            voicePlayer.stop()
            messageAdapter?.setPlayingMessageId(null)
            activeChat?.let { updateSoftkeysForFocusedMessage(it, msg) }
        } else {
            messageAdapter?.setPlayingMessageId(msg.id, voicePlayer.currentSpeed)
            val success = voicePlayer.play(
                path = path,
                onError = {
                    messageAdapter?.setPlayingMessageId(null)
                    activeChat?.let { updateSoftkeysForFocusedMessage(it, msg) }
                    showToast("Unable to play audio recording", true)
                },
                onCompletion = {
                    messageAdapter?.setPlayingMessageId(null)
                    activeChat?.let { updateSoftkeysForFocusedMessage(it, msg) }
                }
            )
            if (!success) {
                messageAdapter?.setPlayingMessageId(null)
                activeChat?.let { updateSoftkeysForFocusedMessage(it, msg) }
                showToast("Unable to play audio recording", true)
            } else {
                activeChat?.let { updateSoftkeysForFocusedMessage(it, msg) }
            }
        }
    }

    // =========================================================================
    // VOICE RECORDING ON FLIP PHONE (Push-to-Talk / Toggle mode)
    // =========================================================================
    private fun startVoiceRecordingFlow(chat: ChatModel) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO_PERM)
            return
        }

        layoutCompose?.visibility = View.GONE
        layoutVoiceRecording?.visibility = View.VISIBLE
        tvRecordTimer?.text = "00:00"

        val started = voiceRecorder.startRecording { elapsedSec ->
            val min = elapsedSec / 60
            val sec = elapsedSec % 60
            tvRecordTimer?.text = String.format("%02d:%02d", min, sec)
        }

        if (!started) {
            cancelVoiceRecordingUI(chat)
            showToast("Failed to start microphone recording", true)
            return
        }

        layoutVoiceRecording?.announceForAccessibility("Voice recording started")

        // Accessible Flip Phone Softkeys: One press of Center/OK to Stop & Send!
        softkeyManager.update(
            left = "Cancel",
            center = "Stop & Send",
            right = "Discard",
            onLeft = { cancelVoiceRecordingUI(chat) },
            onCenter = { stopAndSendVoiceRecording(chat) },
            onRight = { cancelVoiceRecordingUI(chat) }
        )
    }

    private fun stopAndSendVoiceRecording(chat: ChatModel) {
        val recording = voiceRecorder.stopRecording()
        layoutVoiceRecording?.visibility = View.GONE
        layoutCompose?.visibility = View.VISIBLE

        if (recording != null && recording.file.exists()) {
            telegramManager.sendVoiceNote(chat.id, recording.file.absolutePath, recording.durationSeconds)
            showToast("Voice note sent (${recording.durationSeconds}s)!")
        } else {
            showToast("Recording was too short or empty", true)
        }

        restoreConversationSoftkeys(chat)
    }

    private fun cancelVoiceRecordingUI(chat: ChatModel) {
        voiceRecorder.cancelRecording()
        layoutVoiceRecording?.visibility = View.GONE
        layoutCompose?.visibility = View.VISIBLE
        showToast("Recording discarded")
        restoreConversationSoftkeys(chat)
    }

    private fun restoreConversationSoftkeys(chat: ChatModel) {
        softkeyManager.update(
            left = "Attach",
            center = "Send",
            right = "Back",
            onLeft = { showAttachMenu(chat) },
            onCenter = {
                val text = etComposeInput?.text?.toString()?.trim() ?: ""
                if (text.isNotEmpty()) {
                    telegramManager.sendMessage(chat.id, text)
                    etComposeInput?.setText("")
                } else {
                    etComposeInput?.requestFocus()
                }
            },
            onRight = { showChatListScreen() }
        )
        etComposeInput?.requestFocus()
    }

    // =========================================================================
    // LOCATION (GPS on Non-Google Flip Phone + Organic Maps / Google Maps bridge)
    // =========================================================================
    private fun sendLocationFlow(chat: ChatModel) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                REQ_LOC_PERM
            )
            return
        }

        showToast("Acquiring GPS position from hardware...")

        locationHelper.fetchCurrentLocation(
            onSuccess = { locInfo ->
                telegramManager.sendLocation(chat.id, locInfo.latitude, locInfo.longitude, locInfo.accuracyMeters)
                showToast("GPS Location sent! Available on Google Maps and Organic Maps")
            },
            onError = { errMsg ->
                showToast(errMsg, true)
            }
        )
    }

    private fun showAttachMenu(chat: ChatModel) {
        commitActiveInputEngines()
        val options = mutableListOf<MenuOption>()
        if (chat.chatType == ChatType.DIRECT) {
            options.add(MenuOption(options.size + 1, "📞 Call Contact") {
                initiateCallFlow(chat)
            })
        }
        options.add(MenuOption(options.size + 1, "🎤 Record Voice Message (Push-to-Talk)") {
            startVoiceRecordingFlow(chat)
        })
        options.add(MenuOption(options.size + 1, "📍 Send Current GPS Location") {
            sendLocationFlow(chat)
        })
        options.add(MenuOption(options.size + 1, "Symbols & Emoji (*)") {
            showSymbolsDialog { sym -> composeInputEngine?.insertSymbol(sym) }
        })
        options.add(MenuOption(options.size + 1, "Quick Replies") {
            showQuickRepliesMenu(chat)
        })
        options.add(MenuOption(options.size + 1, "Chat Info") {
            showToast("${chat.title} (${chat.chatType})")
        })
        OptionsMenuDialog(this, "Attach & Actions", options).show()
    }

    private fun initiateCallFlow(chat: ChatModel) {
        if (chat.chatType != ChatType.DIRECT) {
            showToast("Direct voice calling is only available for 1-on-1 chats")
            return
        }

        val userId = telegramManager.getDirectChatUserId(chat.id) ?: chat.id
        val phoneNumber = telegramManager.getUserPhoneNumber(userId)

        val options = mutableListOf<MenuOption>()

        if (!phoneNumber.isNullOrBlank()) {
            options.add(MenuOption(options.size + 1, "📞 Phone Call ($phoneNumber)") {
                val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phoneNumber"))
                try {
                    startActivity(dialIntent)
                } catch (e: Exception) {
                    showToast("Could not open phone dialer: ${e.message}", true)
                }
            })
        } else {
            options.add(MenuOption(options.size + 1, "📞 Cellular Dialer") {
                val dialIntent = Intent(Intent.ACTION_DIAL)
                try {
                    startActivity(dialIntent)
                } catch (e: Exception) {
                    showToast("Could not open phone dialer: ${e.message}", true)
                }
            })
        }

        options.add(MenuOption(options.size + 1, "📱 Telegram Voice Call") {
            showToast("Connecting Telegram Voice Call to ${chat.title}...")
            telegramManager.initiateTelegramCall(userId) { success, msg ->
                if (success) {
                    showToast("Calling ${chat.title}...")
                } else {
                    showToast(msg ?: "Telegram call failed", true)
                }
            }
        })

        OptionsMenuDialog(this, "Call ${chat.title}", options).show()
    }

    private fun showForwardDialog(msg: MessageModel) {
        val allChats = telegramManager.chats.value
        val targetChats = allChats.filter { it.id != msg.chatId }
        if (targetChats.isEmpty()) {
            showToast("No other chats available to forward to")
            return
        }

        val options = targetChats.take(20).mapIndexed { idx, chat ->
            MenuOption(idx + 1, "↪ ${chat.title} (${chat.chatType})") {
                telegramManager.forwardMessage(msg.chatId, chat.id, msg.id) { success ->
                    if (success) {
                        showToast("Forwarded to ${chat.title}")
                    } else {
                        showToast("Failed to forward message", true)
                    }
                }
            }
        }

        OptionsMenuDialog(this, "Forward Message To...", options).show()
    }

    private fun saveMessageToDevice(msg: MessageModel) {
        val path = msg.mediaFilePath
        if (msg.mediaType != MediaType.NONE && !path.isNullOrBlank() && File(path).exists()) {
            try {
                val sourceFile = File(path)
                val targetDirType = when (msg.mediaType) {
                    MediaType.PHOTO -> Environment.DIRECTORY_PICTURES
                    MediaType.VOICE -> Environment.DIRECTORY_MUSIC
                    MediaType.VIDEO -> Environment.DIRECTORY_MOVIES
                    else -> Environment.DIRECTORY_DOWNLOADS
                }

                val publicDir = File(Environment.getExternalStoragePublicDirectory(targetDirType), "TeleFlip")
                if (!publicDir.exists()) {
                    publicDir.mkdirs()
                }

                val extension = sourceFile.extension.ifBlank {
                    when (msg.mediaType) {
                        MediaType.PHOTO -> "jpg"
                        MediaType.VOICE -> "m4a"
                        MediaType.VIDEO -> "mp4"
                        else -> "bin"
                    }
                }

                val baseName = if (msg.mediaCaption.isNotBlank() && !msg.mediaCaption.contains("\n")) {
                    msg.mediaCaption.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(30)
                } else {
                    "TeleFlip_${msg.id}"
                }
                val destFile = File(publicDir, "$baseName.$extension")

                sourceFile.copyTo(destFile, overwrite = true)

                MediaScannerConnection.scanFile(
                    this,
                    arrayOf(destFile.absolutePath),
                    null
                ) { scannedPath, _ ->
                    Log.i("TeleFlip", "Media indexed: $scannedPath")
                }

                showToast("Saved to ${publicDir.name}/${destFile.name}")
            } catch (e: Exception) {
                Log.e("TeleFlip", "Failed to save file: ${e.message}", e)
                showToast("Failed to save file: ${e.message}", true)
            }
        } else if (msg.mediaFileId != 0) {
            telegramManager.downloadFilePriority(msg.mediaFileId)
            showToast("Downloading media to device... Please retry once complete.")
        } else if (msg.text.isNotBlank()) {
            try {
                val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "TeleFlip")
                if (!publicDir.exists()) {
                    publicDir.mkdirs()
                }
                val destFile = File(publicDir, "TeleFlip_Note_${msg.id}.txt")
                destFile.writeText("Chat: ${activeChat?.title ?: ""}\nSender: ${msg.senderName}\nTime: ${msg.time}\n\n${msg.text}\n")

                MediaScannerConnection.scanFile(
                    this,
                    arrayOf(destFile.absolutePath),
                    null,
                    null
                )
                showToast("Saved note to Downloads/TeleFlip")
            } catch (e: Exception) {
                Log.e("TeleFlip", "Failed to save note: ${e.message}", e)
                showToast("Failed to save note: ${e.message}", true)
            }
        } else {
            showToast("Nothing to save in this message")
        }
    }

    private fun showMessageOptionsMenu(msg: MessageModel) {
        commitActiveInputEngines()
        val options = mutableListOf<MenuOption>()

        if (msg.status == MessageStatus.FAILED) {
            options.add(MenuOption(options.size + 1, "Resend Message") {
                handleResendMessage(msg)
            })
        }

        options.add(MenuOption(options.size + 1, "Copy Text") {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Telegram Message", msg.text))
            showToast("Copied to clipboard")
        })
        options.add(MenuOption(options.size + 1, "Reply") {
            etComposeInput?.requestFocus()
            etComposeInput?.setText("Re: \"${msg.text.take(20)}...\" ")
            etComposeInput?.setSelection(etComposeInput?.text?.length ?: 0)
        })

        options.add(MenuOption(options.size + 1, "↪ Forward Message") {
            showForwardDialog(msg)
        })

        if (msg.mediaType != MediaType.NONE) {
            options.add(MenuOption(options.size + 1, "💾 Save Media to Device") {
                saveMessageToDevice(msg)
            })
        } else if (msg.text.isNotBlank()) {
            options.add(MenuOption(options.size + 1, "💾 Save Text to File") {
                saveMessageToDevice(msg)
            })
        }

        if (msg.mediaType == MediaType.LOCATION) {
            options.add(MenuOption(options.size + 1, "Open in Organic Maps") {
                LocationHelper.openInOrganicMaps(this, msg.latitude, msg.longitude)
            })
            options.add(MenuOption(options.size + 1, "Copy Google Maps Link") {
                LocationHelper.copyGoogleMapsLink(this, msg.latitude, msg.longitude)
            })
        }

        if (msg.mediaType == MediaType.VOICE) {
            val isPlaying = msg.mediaFilePath != null && voicePlayer.isPlayingPath(msg.mediaFilePath)
            options.add(MenuOption(options.size + 1, if (isPlaying) "Pause Voice Note" else "Play Voice Note") {
                handlePlayVoiceMessage(msg)
            })
            options.add(MenuOption(options.size + 1, "Speed 1.0x") {
                voicePlayer.setPlaybackSpeed(1.0f)
                messageAdapter?.setPlaybackSpeed(1.0f)
                activeChat?.let { updateSoftkeysForFocusedMessage(it, msg) }
            })
            options.add(MenuOption(options.size + 1, "Speed 1.5x") {
                voicePlayer.setPlaybackSpeed(1.5f)
                messageAdapter?.setPlaybackSpeed(1.5f)
                activeChat?.let { updateSoftkeysForFocusedMessage(it, msg) }
            })
            options.add(MenuOption(options.size + 1, "Speed 2.0x") {
                voicePlayer.setPlaybackSpeed(2.0f)
                messageAdapter?.setPlaybackSpeed(2.0f)
                activeChat?.let { updateSoftkeysForFocusedMessage(it, msg) }
            })
        }

        options.add(MenuOption(options.size + 1, "Delete Message") {
            telegramManager.deleteMessage(msg.chatId, msg.id)
            showToast("Message deleted")
        })

        OptionsMenuDialog(this, "Message Actions", options).show()
    }

    private fun showQuickRepliesMenu(chat: ChatModel) {
        commitActiveInputEngines()
        val quicks = listOf(
            "OK! 👍", "On my way! 🚗", "Call you in a few minutes.",
            "Sounds good!", "Can't talk right now, will reply later.",
            "Sent from my Android Flip Phone 📱"
        )
        val options = quicks.mapIndexed { index, text ->
            MenuOption(index + 1, text) {
                telegramManager.sendMessage(chat.id, text)
            }
        }
        OptionsMenuDialog(this, "Quick Replies", options).show()
    }

    private fun showSymbolsDialog(onSelected: (String) -> Unit) {
        commitActiveInputEngines()
        SymbolsDialog(this, onSelected).show()
    }

    // =========================================================================
    // SCREEN 3: LOGIN
    // =========================================================================
    private fun showLoginScreen(state: AuthState) {
        commitActiveInputEngines()
        currentScreen = Screen.LOGIN
        mainContainer.removeAllViews()

        val view = LayoutInflater.from(this).inflate(R.layout.view_login, mainContainer, true)

        val tvStatus = view.findViewById<TextView>(R.id.tv_login_status)
        val tvLabel = view.findViewById<TextView>(R.id.tv_input_label)
        val tvMode = view.findViewById<TextView>(R.id.tv_login_input_mode)
        etLoginInput = view.findViewById(R.id.et_login_input)
        val btnSubmit = view.findViewById<Button>(R.id.btn_login_submit)

        val btnRetry = view.findViewById<Button>(R.id.btn_retry_connection)
        val btnForgotPassword = view.findViewById<Button>(R.id.btn_forgot_password)
        val btnResetAccount = view.findViewById<Button>(R.id.btn_reset_account)
        val btnUseSim = view.findViewById<Button>(R.id.btn_use_sim_number)
        val btnDemo = view.findViewById<Button>(R.id.btn_demo_mode)
        val btnConfig = view.findViewById<Button>(R.id.btn_config_credentials)

        loginInputEngine = KeypadInputEngine(
            editText = etLoginInput!!,
            modeIndicator = tvMode,
            timeoutMs = telegramManager.keypadTimeoutMs
        ) {
            showSymbolsDialog { symbol -> loginInputEngine?.insertSymbol(symbol) }
        }

btnRetry?.setOnClickListener {
            telegramManager.retryConnection()
        }
        btnForgotPassword?.visibility = View.GONE
        btnResetAccount?.visibility = View.GONE

        when (state) {
            is AuthState.WaitPhoneNumber -> {
                tvMode?.visibility = View.VISIBLE
                loginInputEngine?.setMode(com.teleflip.app.input.InputMode.NUMERIC)
                tvLabel.text = "Phone Number (with +country code):"
                etLoginInput?.hint = "+1 555 123 4567"
                btnSubmit.text = "Send Code"

                val detected = getDevicePhoneNumber()
                if (!detected.isNullOrBlank()) {
                    val formatted = org.telegram.PhoneFormat.PhoneFormat.getInstance().format(detected)
                    if (etLoginInput?.text.isNullOrEmpty()) {
                        etLoginInput?.setText(formatted)
                        etLoginInput?.setSelection(etLoginInput?.text?.length ?: 0)
                    }
                    btnUseSim?.visibility = View.VISIBLE
                    btnUseSim?.text = "Use SIM ($formatted)"
                    btnUseSim?.setOnClickListener {
                        etLoginInput?.setText(formatted)
                        etLoginInput?.setSelection(etLoginInput?.text?.length ?: 0)
                        btnSubmit.requestFocus()
                    }
                } else {
                    btnUseSim?.visibility = View.GONE
                }

                if (state.errorMessage != null) {
                    val errorMessage = "⚠️ Error: ${state.errorMessage}"
                    tvStatus.text = errorMessage
                    tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_error))
                    tvStatus.announceForAccessibility(errorMessage)
                } else if (!detected.isNullOrBlank()) {
                    tvStatus.text = "Detected SIM phone number"
                    tvStatus.setTextColor(ContextCompat.getColor(this, R.color.focus_highlight_gold))
                } else {
                    tvStatus.text = "Enter phone number linked to Telegram"
                    tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
                }
            }
            is AuthState.WaitCode -> {
                tvMode?.visibility = View.GONE
                loginInputEngine?.setMode(com.teleflip.app.input.InputMode.NUMERIC)
                btnUseSim?.visibility = View.GONE
                tvLabel.text = "Verification Code (5 digits):"
                etLoginInput?.hint = "12345"
                btnSubmit.text = "Verify Code"
                if (state.errorMessage != null) {
                    val errorMessage = "⚠️ Error: ${state.errorMessage}"
                    tvStatus.text = errorMessage
                    tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_error))
                    tvStatus.announceForAccessibility(errorMessage)
                } else {
                    tvStatus.text = "Enter code sent via Telegram or SMS"
                    tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
                }
            }
            is AuthState.WaitPassword -> {
                tvMode?.visibility = View.VISIBLE
                loginInputEngine?.setMode(com.teleflip.app.input.InputMode.LOWER)
                btnUseSim?.visibility = View.GONE
btnForgotPassword?.visibility = View.VISIBLE
                btnResetAccount?.visibility = View.VISIBLE

                btnSubmit.nextFocusDownId = R.id.btn_forgot_password
                btnForgotPassword?.nextFocusUpId = R.id.btn_login_submit
                btnForgotPassword?.nextFocusDownId = R.id.btn_reset_account
                btnResetAccount?.nextFocusUpId = R.id.btn_forgot_password
                btnResetAccount?.nextFocusDownId = R.id.btn_demo_mode
                btnDemo.nextFocusUpId = R.id.btn_reset_account

                btnForgotPassword?.setOnClickListener {
                    telegramManager.requestPasswordRecovery()
                }
                btnResetAccount?.setOnClickListener {
                    showAccountResetConfirmationDialog()
                }
                tvLabel.text = "2-Step Verification Password:"
                etLoginInput?.hint = "Cloud Password"
                btnSubmit.text = "Submit Password"
                if (state.errorMessage != null) {
                    val errorMessage = "⚠️ Error: ${state.errorMessage}"
                    tvStatus.text = errorMessage
                    tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_error))
                    tvStatus.announceForAccessibility(errorMessage)
                } else {
                    val statusText = buildString {
                        if (state.hint.isNotBlank()) append("Hint: ${state.hint}") else append("Enter 2-Step Verification Password")
                        if (state.hasRecoveryEmailAddress && state.recoveryEmailAddressPattern.isNotBlank()) {
                            append(" • Email: ${state.recoveryEmailAddressPattern}")
                        }
                    }
                    tvStatus.text = statusText
                    tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
                }
            }
            is AuthState.WaitPasswordRecoveryCode -> {
                tvMode?.visibility = View.GONE
                loginInputEngine?.setMode(com.teleflip.app.input.InputMode.NUMERIC)
                btnUseSim?.visibility = View.GONE
                btnForgotPassword?.visibility = View.GONE
                btnResetAccount?.visibility = View.GONE

                btnSubmit.nextFocusDownId = R.id.btn_demo_mode
                btnDemo.nextFocusUpId = R.id.btn_login_submit

                tvLabel.text = "Email Recovery Code (6 digits):"
                etLoginInput?.hint = "123456"
                etLoginInput?.setText("")
                btnSubmit.text = "Submit Recovery Code"

                if (state.errorMessage != null) {
                    tvStatus.text = "⚠️ Error: ${state.errorMessage}"
                    tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_error))
                } else {
                    tvStatus.text = if (state.recoveryEmailPattern.isNotBlank()) {
                        "Code sent to ${state.recoveryEmailPattern}"
                    } else {
                        "Enter code sent to recovery email"
                    }
                    tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
                }
            }
            is AuthState.Error -> {
                btnUseSim?.visibility = View.GONE
btnSubmit.nextFocusDownId = R.id.btn_demo_mode
                btnDemo.nextFocusUpId = R.id.btn_login_submit
                val errorMessage = "⚠️ Error: ${state.message}"
                tvStatus.text = errorMessage
                tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_error))
                tvStatus.announceForAccessibility(errorMessage)
                com.teleflip.app.report.AdbReporter.reportError("Auth", state.message)
            }
            else -> {}
        }

        val progressLogin = view.findViewById<View>(R.id.progress_login)
        val doSubmit = {
            loginInputEngine?.commitCurrentChar()
            if (telegramManager.isAuthQueryInProgress) {
                com.teleflip.app.report.AdbReporter.reportInfo("Login", "Submit ignored: authorization query already in flight")
            } else {
                val text = etLoginInput?.text?.toString()?.trim() ?: ""
                if (text.isNotEmpty()) {
                    btnSubmit.isEnabled = false
                    btnSubmit.text = "Sending..."
                    tvStatus.text = "Contacting Telegram servers..."
                    tvStatus.setTextColor(ContextCompat.getColor(this, R.color.focus_highlight_gold))
                    progressLogin?.visibility = View.VISIBLE
                    com.teleflip.app.report.AdbReporter.reportInfo("Login", "Submitting credentials/code: $text")
                    when (telegramManager.authState.value) {
                        is AuthState.WaitPhoneNumber -> telegramManager.sendPhoneNumber(text)
                        is AuthState.WaitCode -> telegramManager.checkCode(text)
                        is AuthState.WaitPassword -> telegramManager.checkPassword(text)
                        is AuthState.WaitPasswordRecoveryCode -> telegramManager.recoverPassword(text)
                        else -> {
                            btnSubmit.isEnabled = true
                            progressLogin?.visibility = View.GONE
                        }
                    }
                } else {
                    showToast("Please enter a phone number, or press [Demo] (Right Softkey)", true)
                }
            }
        }

        btnSubmit.setOnClickListener { doSubmit() }
        btnDemo.setOnClickListener {
            telegramManager.enableDemoMode()
            showChatListScreen()
        }
        btnConfig.setOnClickListener { showSettingsScreen() }

        updateLoginConnectionState(telegramManager.connectionState.value)
        updateLoginProgressState(telegramManager.isAuthQueryInProgress)

        softkeyManager.update(
            left = "Config",
            center = "Submit",
            right = "Demo",
            onLeft = { showSettingsScreen() },
            onCenter = {
                if (currentFocus?.id == R.id.btn_retry_connection) {
                    telegramManager.retryConnection()
                } else {
                    doSubmit()
                }
            },
            onRight = {
                telegramManager.enableDemoMode()
                showChatListScreen()
            }
        )

        etLoginInput?.requestFocus()
    }

    private fun updateLoginConnectionState(status: String) {
        val btnRetry = findViewById<Button>(R.id.btn_retry_connection) ?: return
        val tvStatus = findViewById<TextView>(R.id.tv_login_status) ?: return

        if (status == "Init error") {
            btnRetry.visibility = View.VISIBLE
            btnRetry.setOnClickListener { telegramManager.retryConnection() }
            val currentAuth = telegramManager.authState.value
            val err = when (currentAuth) {
                is AuthState.WaitPhoneNumber -> currentAuth.errorMessage
                is AuthState.WaitCode -> currentAuth.errorMessage
                is AuthState.WaitPassword -> currentAuth.errorMessage
                is AuthState.Error -> currentAuth.message
                else -> null
            }
            tvStatus.text = err?.let { "⚠️ Error: $it" } ?: "⚠️ Connection Init Error. Native client failed to start."
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_error))
        } else {
            btnRetry.visibility = View.GONE
        }
        updateLoginFocusChain()
    }

    private fun updateLoginProgressState(inProgress: Boolean) {
        val btnSubmit = findViewById<Button>(R.id.btn_login_submit) ?: return
        val progressLogin = findViewById<View>(R.id.progress_login) ?: return

        if (inProgress) {
            btnSubmit.isEnabled = false
            btnSubmit.text = "Sending..."
            progressLogin.visibility = View.VISIBLE
        } else {
            btnSubmit.isEnabled = true
            progressLogin.visibility = View.GONE
            val defaultText = when (telegramManager.authState.value) {
                is AuthState.WaitCode -> "Verify Code"
                is AuthState.WaitPassword -> "Submit Password"
                else -> "Send Code"
            }
            btnSubmit.text = defaultText
        }
    }

    private fun updateLoginFocusChain() {
        val etInput = etLoginInput ?: return
        val btnSubmit = findViewById<Button>(R.id.btn_login_submit) ?: return
        val btnRetry = findViewById<Button>(R.id.btn_retry_connection) ?: return
        val btnSim = findViewById<Button>(R.id.btn_use_sim_number) ?: return
        val btnDemo = findViewById<Button>(R.id.btn_demo_mode) ?: return
        val btnConfig = findViewById<Button>(R.id.btn_config_credentials) ?: return

        etInput.nextFocusDownId = R.id.btn_login_submit
        btnSubmit.nextFocusUpId = R.id.et_login_input

        var prevId = R.id.btn_login_submit

        if (btnRetry.visibility == View.VISIBLE) {
            findViewById<View>(prevId)?.nextFocusDownId = R.id.btn_retry_connection
            btnRetry.nextFocusUpId = prevId
            prevId = R.id.btn_retry_connection
        }

        if (btnSim.visibility == View.VISIBLE) {
            findViewById<View>(prevId)?.nextFocusDownId = R.id.btn_use_sim_number
            btnSim.nextFocusUpId = prevId
            prevId = R.id.btn_use_sim_number
        }

        findViewById<View>(prevId)?.nextFocusDownId = R.id.btn_demo_mode
        btnDemo.nextFocusUpId = prevId

        btnDemo.nextFocusDownId = R.id.btn_config_credentials
        btnConfig.nextFocusUpId = R.id.btn_demo_mode
    }

    private fun showAccountResetConfirmationDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Reset Account?")
            .setMessage("Warning: Resetting your account will permanently delete your session and cloud messages for this account. This action cannot be undone. Are you sure you want to reset your account?")
            .setPositiveButton("Reset Account") { dialog, _ ->
                dialog.dismiss()
                telegramManager.resetAccount()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun hasTelephonyPermission(): Boolean {
        val hasPhoneState = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val hasPhoneNumbers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED
        } else {
            false
        }
        return hasPhoneState || hasPhoneNumbers
    }

    private fun getDevicePhoneNumber(): String? {
        if (!hasTelephonyPermission()) {
            return null
        }

        val sdkInt = Build.VERSION.SDK_INT

        // Branch 1: API Level 33 and newer (Android 13+ / TIRAMISU+)
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val sm = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
                if (sm != null) {
                    try {
                        @Suppress("MissingPermission")
                        val activeList = sm.activeSubscriptionInfoList
                        if (!activeList.isNullOrEmpty()) {
                            for (sub in activeList) {
                                try {
                                    @Suppress("MissingPermission")
                                    val raw = sm.getPhoneNumber(sub.subscriptionId)
                                    val formatted = cleanAndFormatPhoneNumber(raw)
                                    if (formatted != null) return formatted
                                } catch (e: SecurityException) {
                                    org.telegram.messenger.FileLog.e("getDevicePhoneNumber API 33+ sub SecurityException: ${e.message}")
                                } catch (e: Throwable) {
                                    org.telegram.messenger.FileLog.e("getDevicePhoneNumber API 33+ sub getPhoneNumber failed: ${e.message}")
                                }
                            }
                        }
                    } catch (e: SecurityException) {
                        org.telegram.messenger.FileLog.e("getDevicePhoneNumber API 33+ activeSubscriptionInfoList SecurityException: ${e.message}")
                    } catch (e: Throwable) {
                        org.telegram.messenger.FileLog.e("getDevicePhoneNumber API 33+ activeSubscriptionInfoList failed: ${e.message}")
                    }

                    // Fallback to DEFAULT_SUBSCRIPTION_ID on API 33+
                    try {
                        @Suppress("MissingPermission")
                        val raw = sm.getPhoneNumber(SubscriptionManager.DEFAULT_SUBSCRIPTION_ID)
                        val formatted = cleanAndFormatPhoneNumber(raw)
                        if (formatted != null) return formatted
                    } catch (e: SecurityException) {
                        org.telegram.messenger.FileLog.e("getDevicePhoneNumber API 33+ DEFAULT_SUBSCRIPTION_ID SecurityException: ${e.message}")
                    } catch (e: Throwable) {
                        org.telegram.messenger.FileLog.e("getDevicePhoneNumber API 33+ DEFAULT_SUBSCRIPTION_ID failed: ${e.message}")
                    }
                }
            } catch (e: SecurityException) {
                org.telegram.messenger.FileLog.e("getDevicePhoneNumber API 33+ SubscriptionManager SecurityException: ${e.message}")
            } catch (e: Throwable) {
                org.telegram.messenger.FileLog.e("getDevicePhoneNumber API 33+ failed: ${e.message}")
            }
            return null
        }

        // Branch 2: API Level 30 to 32 (Android 11 to Android 12L / R..S_V2)
        if (sdkInt >= Build.VERSION_CODES.R) {
            try {
                val sm = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
                if (sm != null) {
                    @Suppress("MissingPermission", "DEPRECATION")
                    val activeList = sm.activeSubscriptionInfoList
                    if (!activeList.isNullOrEmpty()) {
                        for (sub in activeList) {
                            @Suppress("DEPRECATION")
                            val raw = sub.number
                            val formatted = cleanAndFormatPhoneNumber(raw)
                            if (formatted != null) return formatted
                        }
                    }
                }
            } catch (e: SecurityException) {
                org.telegram.messenger.FileLog.e("getDevicePhoneNumber API 30-32 SubscriptionManager SecurityException: ${e.message}")
            } catch (e: Throwable) {
                org.telegram.messenger.FileLog.e("getDevicePhoneNumber API 30-32 SubscriptionManager failed: ${e.message}")
            }

            // Fallback to TelephonyManager for API 30-32
            try {
                val tm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                if (tm != null) {
                    @Suppress("MissingPermission", "DEPRECATION")
                    val raw = tm.line1Number
                    val formatted = cleanAndFormatPhoneNumber(raw)
                    if (formatted != null) return formatted
                }
            } catch (e: SecurityException) {
                org.telegram.messenger.FileLog.e("getDevicePhoneNumber API 30-32 TelephonyManager SecurityException: ${e.message}")
            } catch (e: Throwable) {
                org.telegram.messenger.FileLog.e("getDevicePhoneNumber API 30-32 TelephonyManager failed: ${e.message}")
            }

            return null
        }

        // Branch 3: API Level below 30 (Legacy API levels < 30)
        try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            if (tm != null) {
                @Suppress("MissingPermission", "DEPRECATION")
                val raw = tm.line1Number
                val formatted = cleanAndFormatPhoneNumber(raw)
                if (formatted != null) return formatted
            }
        } catch (e: SecurityException) {
            org.telegram.messenger.FileLog.e("getDevicePhoneNumber legacy TelephonyManager SecurityException: ${e.message}")
        } catch (e: Throwable) {
            org.telegram.messenger.FileLog.e("getDevicePhoneNumber legacy TelephonyManager failed: ${e.message}")
        }

        try {
            val sm = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            if (sm != null) {
                @Suppress("MissingPermission", "DEPRECATION")
                val activeList = sm.activeSubscriptionInfoList
                if (!activeList.isNullOrEmpty()) {
                    for (sub in activeList) {
                        @Suppress("DEPRECATION")
                        val raw = sub.number
                        val formatted = cleanAndFormatPhoneNumber(raw)
                        if (formatted != null) return formatted
                    }
                }
            }
        } catch (e: SecurityException) {
            org.telegram.messenger.FileLog.e("getDevicePhoneNumber legacy SubscriptionManager SecurityException: ${e.message}")
        } catch (e: Throwable) {
            org.telegram.messenger.FileLog.e("getDevicePhoneNumber legacy SubscriptionManager failed: ${e.message}")
        }

        return null
    }

    // =========================================================================
    // SCREEN 4: SETTINGS & CONFIGURATION
    // =========================================================================
    private fun showSettingsScreen() {
        commitActiveInputEngines()
        currentScreen = Screen.SETTINGS
        mainContainer.removeAllViews()

        val view = LayoutInflater.from(this).inflate(R.layout.view_settings, mainContainer, true)

        val etId = view.findViewById<EditText>(R.id.et_api_id)
        val etHash = view.findViewById<EditText>(R.id.et_api_hash)
        val spTimeout = view.findViewById<Spinner>(R.id.sp_keypad_timeout)
        val btnSave = view.findViewById<Button>(R.id.btn_save_config)
        val btnLogout = view.findViewById<Button>(R.id.btn_logout)
        val btnBack = view.findViewById<Button>(R.id.btn_back_to_chats)

        etId.setText(telegramManager.apiId.toString())
        etHash.setText(telegramManager.apiHash)

        val timeoutValues = listOf(300L, 500L, 900L)
        val timeoutLabels = listOf("300 ms (Fast)", "500 ms (Normal)", "900 ms (Slow)")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, timeoutLabels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spTimeout.adapter = adapter

        val currentTimeout = telegramManager.keypadTimeoutMs
        val initialIndex = when (currentTimeout) {
            300L -> 0
            900L -> 2
            else -> 1
        }
        spTimeout.setSelection(initialIndex)

        val doSave = {
            val selIndex = spTimeout.selectedItemPosition
            if (selIndex in timeoutValues.indices) {
                telegramManager.keypadTimeoutMs = timeoutValues[selIndex]
            }
            val idStr = etId.text.toString().trim()
            val hashStr = etHash.text.toString().trim()
            if (idStr.isNotEmpty() && hashStr.isNotEmpty()) {
                telegramManager.apiId = idStr.toIntOrNull() ?: telegramManager.apiId
                telegramManager.apiHash = hashStr
                showToast("Saved! Reconnecting...")
                telegramManager.start()
                showChatListScreen()
            } else {
                showToast("Saved!")
            }
        }

        btnSave.setOnClickListener { doSave() }
        btnLogout.setOnClickListener {
            telegramManager.logOut()
            showLoginScreen(AuthState.WaitPhoneNumber())
        }
        btnBack.setOnClickListener {
            if (telegramManager.authState.value is AuthState.Ready) {
                showChatListScreen()
            } else {
                showLoginScreen(telegramManager.authState.value)
            }
        }

        softkeyManager.update(
            left = "Save",
            center = "Save",
            right = "Back",
            onLeft = { doSave() },
            onCenter = { doSave() },
            onRight = {
                if (telegramManager.authState.value is AuthState.Ready) {
                    showChatListScreen()
                } else {
                    showLoginScreen(telegramManager.authState.value)
                }
            }
        )

        etId.requestFocus()
    }

    // =========================================================================
    // HARDWARE KEYPAD & D-PAD EVENT ROUTING
    // =========================================================================
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode

        // Top Left hardware softkey always triggers softkey action
        if (SoftkeyUtils.isSoftLeftKey(keyCode)) {
            if (softkeyManager.handleKeyEvent(keyCode, event)) return true
        }

        // Top Right hardware softkey handling (isolated from KEYCODE_BACK)
        if (SoftkeyUtils.isSoftRightKey(keyCode)) {
            if (softkeyManager.handleKeyEvent(keyCode, event)) return true
        }

        // Dedicated Call button: Start call in direct chat or voice note in group/channel
        if (keyCode == KeyEvent.KEYCODE_CALL && event.action == KeyEvent.ACTION_DOWN) {
            if (currentScreen == Screen.CONVERSATION) {
                activeChat?.let { chat ->
                    if (voiceRecorder.isRecording()) {
                        stopAndSendVoiceRecording(chat)
                    } else if (chat.chatType == ChatType.DIRECT) {
                        initiateCallFlow(chat)
                    } else {
                        startVoiceRecordingFlow(chat)
                    }
                }
                return true
            }
        }

        // D-Pad Center / Enter Key
        if (SoftkeyUtils.isCenterKey(keyCode) && event.action == KeyEvent.ACTION_DOWN) {
            if (voiceRecorder.isRecording()) {
                activeChat?.let { stopAndSendVoiceRecording(it) }
                return true
            }
            val focused = currentFocus
            if (focused is Button) {
                focused.performClick()
                return true
            }
            if (softkeyManager.handleKeyEvent(keyCode, event)) {
                return true
            }
        }

        // In Conversation: Route *, # to compose box engine
        if (currentScreen == Screen.CONVERSATION && etComposeInput?.hasFocus() == true) {
            if (composeInputEngine?.handleKeyEvent(keyCode, event) == true) {
                return true
            }
        }

        // In Login: Route keypad keys to login input engine
        if (currentScreen == Screen.LOGIN) {
            val isInputKey = (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) ||
                             keyCode == KeyEvent.KEYCODE_STAR ||
                             keyCode == KeyEvent.KEYCODE_POUND ||
                             keyCode == KeyEvent.KEYCODE_DEL ||
                             keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
            if (isInputKey) {
                if (etLoginInput?.hasFocus() != true) {
                    etLoginInput?.requestFocus()
                }
                if (loginInputEngine?.handleKeyEvent(keyCode, event) == true) {
                    return true
                }
            }
        }

        // In Chat List: Keypad numbers 1-9 directly open chat 1-9!
        if (currentScreen == Screen.CHAT_LIST && event.action == KeyEvent.ACTION_DOWN) {
            if (keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9) {
                val index = keyCode - KeyEvent.KEYCODE_1
                val target = chatListAdapter?.getItemAt(index)
                if (target != null) {
                    openConversation(target)
                    return true
                }
            }

            if (isTabViewFocused()) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    // Suppress cycleTabs to avoid double key activation when tab TextView holds focus
                    return super.dispatchKeyEvent(event)
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    val itemCount = chatListAdapter?.itemCount ?: 0
                    if (itemCount > 0) {
                        rvChats?.scrollToPosition(0)
                        rvChats?.post {
                            val vh = rvChats?.findViewHolderForAdapterPosition(0)
                            if (vh != null) {
                                vh.itemView.requestFocus()
                            } else {
                                rvChats?.requestFocus()
                            }
                        }
                    } else {
                        findViewById<View>(R.id.tv_softkey_left)?.requestFocus()
                    }
                    return true
                }
            } else {
                // Left/Right D-pad keys cycle filter tabs when list is focused
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    cycleTabs(-1)
                    return true
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    cycleTabs(1)
                    return true
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    val focusedView = currentFocus
                    val rv = rvChats
                    if (focusedView != null && rv != null) {
                        val itemView = rv.findContainingItemView(focusedView)
                        if (itemView != null && rv.getChildAdapterPosition(itemView) == 0) {
                            val targetTabId = when (currentChatTypeFilter) {
                                ChatType.DIRECT -> R.id.tab_direct
                                ChatType.GROUP -> R.id.tab_groups
                                ChatType.CHANNEL -> R.id.tab_channels
                                else -> R.id.tab_all
                            }
                            findViewById<View>(targetTabId)?.requestFocus()
                            return true
                        }
                    }
                }
            }
        }

        return super.dispatchKeyEvent(event)
    }

    private fun cycleTabs(direction: Int) {
        val tabOrder = listOf(null, ChatType.DIRECT, ChatType.GROUP, ChatType.CHANNEL)
        val currentIndex = tabOrder.indexOf(currentChatTypeFilter)
        val nextIndex = (currentIndex + direction + tabOrder.size) % tabOrder.size
        applyChatFilterAndDebounce(tabOrder[nextIndex])
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO_PERM && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            activeChat?.let { startVoiceRecordingFlow(it) }
        }
        if (requestCode == REQ_LOC_PERM && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            activeChat?.let { sendLocationFlow(it) }
        }
        if (requestCode == REQ_PHONE_PERM && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (currentScreen == Screen.LOGIN) {
                showLoginScreen(telegramManager.authState.value)
            }
        }
    }

    override fun onBackPressed() {
        super.onBackPressed()
    }
}
