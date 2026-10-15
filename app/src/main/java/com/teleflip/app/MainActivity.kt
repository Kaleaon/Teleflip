package com.teleflip.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
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
import com.teleflip.app.telegram.AuthState
import com.teleflip.app.telegram.TelegramManager
import com.teleflip.app.ui.ChatListAdapter
import com.teleflip.app.ui.MenuOption
import com.teleflip.app.ui.MessageAdapter
import com.teleflip.app.ui.OptionsMenuDialog
import com.teleflip.app.ui.SoftkeyManager
import com.teleflip.app.ui.SymbolsDialog
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

    // Chat List State
    private var currentChatTypeFilter: ChatType? = null
    private var chatListAdapter: ChatListAdapter? = null
    private var rvChats: RecyclerView? = null

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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        com.teleflip.app.report.AdbReporter.init(this)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_PHONE_STATE), REQ_PHONE_PERM)
        }

        mainContainer = findViewById(R.id.main_container)

        val tvLsk = findViewById<TextView>(R.id.tv_softkey_left)
        val tvCenter = findViewById<TextView>(R.id.tv_softkey_center)
        val tvRsk = findViewById<TextView>(R.id.tv_softkey_right)
        softkeyManager = SoftkeyManager(tvLsk, tvCenter, tvRsk)

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
                    is AuthState.WaitPhoneNumber, is AuthState.WaitCode, is AuthState.WaitPassword, is AuthState.Error -> {
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
            }
        }

        lifecycleScope.launch {
            telegramManager.chats.collectLatest { chats ->
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

                    if (filtered.isEmpty()) {
                        if (chats.isEmpty() && telegramManager.connectionState.value != "Offline") {
                            progressView?.visibility = View.VISIBLE
                            emptyView?.visibility = View.GONE
                        } else {
                            progressView?.visibility = View.GONE
                            emptyView?.visibility = View.VISIBLE
                        }
                    } else {
                        progressView?.visibility = View.GONE
                        emptyView?.visibility = View.GONE
                        if (wasEmpty) {
                            rvChats?.post {
                                rvChats?.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
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
                        if (list.isNotEmpty()) {
                            rvMessages?.scrollToPosition(list.size - 1)
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // SCREEN 1: CHAT LIST
    // =========================================================================
    private fun showChatListScreen() {
        currentScreen = Screen.CHAT_LIST
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

        val chats = telegramManager.chats.value
        val filtered = if (currentChatTypeFilter == null) chats else chats.filter { it.chatType == currentChatTypeFilter }
        chatListAdapter?.submitList(filtered)
        val emptyView = view.findViewById<View>(R.id.layout_empty_state)
        val progressView = view.findViewById<View>(R.id.progress_chats)
        if (filtered.isEmpty()) {
            if (chats.isEmpty() && telegramManager.connectionState.value != "Offline") {
                progressView?.visibility = View.VISIBLE
                emptyView?.visibility = View.GONE
            } else {
                progressView?.visibility = View.GONE
                emptyView?.visibility = View.VISIBLE
            }
        } else {
            progressView?.visibility = View.GONE
            emptyView?.visibility = View.GONE
        }
    }

    private fun setupTabs(rootView: View) {
        val tabAll = rootView.findViewById<TextView>(R.id.tab_all)
        val tabDirect = rootView.findViewById<TextView>(R.id.tab_direct)
        val tabGroups = rootView.findViewById<TextView>(R.id.tab_groups)
        val tabChannels = rootView.findViewById<TextView>(R.id.tab_channels)

        val tabs = listOf(tabAll, tabDirect, tabGroups, tabChannels)

        fun selectTab(selected: TextView, filter: ChatType?) {
            tabs.forEach { it.isSelected = (it == selected) }
            currentChatTypeFilter = filter
            val allChats = telegramManager.chats.value
            val filtered = if (filter == null) allChats else allChats.filter { it.chatType == filter }
            chatListAdapter?.submitList(filtered)
            rootView.findViewById<View>(R.id.layout_empty_state)?.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        }

        tabAll.setOnClickListener { selectTab(tabAll, null) }
        tabDirect.setOnClickListener { selectTab(tabDirect, ChatType.DIRECT) }
        tabGroups.setOnClickListener { selectTab(tabGroups, ChatType.GROUP) }
        tabChannels.setOnClickListener { selectTab(tabChannels, ChatType.CHANNEL) }
    }

    private fun showToast(msg: String, isError: Boolean = false) {
        com.teleflip.app.report.AdbReporter.reportPopup("UI_Popup", msg, isError)
        Toast.makeText(this, msg, if (isError) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    private fun showChatListOptionsMenu() {
        val options = listOf(
            MenuOption(1, "New Chat / Contact") {
                showToast("Enter username or number to start chat")
            },
            MenuOption(2, "Refresh Chats") {
                telegramManager.loadChats()
                showToast("Refreshing chats...")
            },
            MenuOption(3, "Explore Demo / Offline Chats") {
                telegramManager.enableDemoMode()
                showToast("Demo mode active")
            },
            MenuOption(4, "Diagnostics & Error Log") {
                com.teleflip.app.ui.DiagnosticsDialog(this).show()
            },
            MenuOption(5, "Settings & API") {
                showSettingsScreen()
            },
            MenuOption(6, "Log Out") {
                telegramManager.logOut()
            }
        )
        OptionsMenuDialog(this, "Chat Options", options).show()
    }

    // =========================================================================
    // SCREEN 2: CONVERSATION / CHAT
    // =========================================================================
    private fun openConversation(chat: ChatModel) {
        activeChat = chat
        currentScreen = Screen.CONVERSATION
        mainContainer.removeAllViews()

        val view = LayoutInflater.from(this).inflate(R.layout.view_chat_conversation, mainContainer, true)

        view.findViewById<TextView>(R.id.tv_conv_title).text = chat.title
        val subtitle = view.findViewById<TextView>(R.id.tv_conv_subtitle)
        subtitle.text = when (chat.chatType) {
            ChatType.CHANNEL -> "Channel"
            ChatType.GROUP -> "Group"
            ChatType.BOT -> "Bot"
            ChatType.DIRECT -> "online"
        }

        val ivConvAvatar = view.findViewById<ImageView>(R.id.iv_conv_avatar)
        val tvConvAvatar = view.findViewById<TextView>(R.id.tv_conv_avatar)
        if (!chat.avatarPath.isNullOrEmpty() && java.io.File(chat.avatarPath).exists()) {
            try {
                val bmp = android.graphics.BitmapFactory.decodeFile(chat.avatarPath)
                if (bmp != null) {
                    val circ = androidx.core.graphics.drawable.RoundedBitmapDrawableFactory.create(resources, bmp).apply { isCircular = true }
                    ivConvAvatar?.setImageDrawable(circ)
                    ivConvAvatar?.visibility = View.VISIBLE
                    tvConvAvatar?.visibility = View.GONE
                } else {
                    ivConvAvatar?.visibility = View.GONE
                    tvConvAvatar?.visibility = View.VISIBLE
                    tvConvAvatar?.setText(chat.avatarLetters)
                }
            } catch (e: Exception) {
                ivConvAvatar?.visibility = View.GONE
                tvConvAvatar?.visibility = View.VISIBLE
                tvConvAvatar?.setText(chat.avatarLetters)
            }
        } else {
            ivConvAvatar?.visibility = View.GONE
            tvConvAvatar?.visibility = View.VISIBLE
            tvConvAvatar?.setText(chat.avatarLetters)
        }

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
        if (currentMsgs.isNotEmpty()) {
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

    private fun updateSoftkeysForFocusedMessage(chat: ChatModel, msg: MessageModel) {
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
        val options = listOf(
            MenuOption(1, "🎤 Record Voice Message (Push-to-Talk)") {
                startVoiceRecordingFlow(chat)
            },
            MenuOption(2, "📍 Send Current GPS Location") {
                sendLocationFlow(chat)
            },
            MenuOption(3, "Symbols & Emoji (*)") {
                showSymbolsDialog { sym -> composeInputEngine?.insertSymbol(sym) }
            },
            MenuOption(4, "Quick Replies") {
                showQuickRepliesMenu(chat)
            },
            MenuOption(5, "Chat Info") {
                showToast("${chat.title} (${chat.chatType})")
            }
        )
        OptionsMenuDialog(this, "Attach & Actions", options).show()
    }

    private fun showMessageOptionsMenu(msg: MessageModel) {
        val options = mutableListOf<MenuOption>()
        options.add(MenuOption(1, "Copy Text") {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Telegram Message", msg.text))
            showToast("Copied to clipboard")
        })
        options.add(MenuOption(2, "Reply") {
            etComposeInput?.requestFocus()
            etComposeInput?.setText("Re: \"${msg.text.take(20)}...\" ")
            etComposeInput?.setSelection(etComposeInput?.text?.length ?: 0)
        })

        if (msg.mediaType == MediaType.LOCATION) {
            options.add(MenuOption(3, "Open in Organic Maps") {
                LocationHelper.openInOrganicMaps(this, msg.latitude, msg.longitude)
            })
            options.add(MenuOption(4, "Copy Google Maps Link") {
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
        SymbolsDialog(this, onSelected).show()
    }

    // =========================================================================
    // SCREEN 3: LOGIN
    // =========================================================================
    private fun showLoginScreen(state: AuthState) {
        currentScreen = Screen.LOGIN
        mainContainer.removeAllViews()

        val view = LayoutInflater.from(this).inflate(R.layout.view_login, mainContainer, true)

        val tvStatus = view.findViewById<TextView>(R.id.tv_login_status)
        val tvLabel = view.findViewById<TextView>(R.id.tv_input_label)
        val tvMode = view.findViewById<TextView>(R.id.tv_login_input_mode)
        etLoginInput = view.findViewById(R.id.et_login_input)
        val btnSubmit = view.findViewById<Button>(R.id.btn_login_submit)
        val btnUseSim = view.findViewById<Button>(R.id.btn_use_sim_number)
        val btnDemo = view.findViewById<Button>(R.id.btn_demo_mode)
        val btnConfig = view.findViewById<Button>(R.id.btn_config_credentials)

        loginInputEngine = KeypadInputEngine(etLoginInput!!, tvMode) {
            showSymbolsDialog { symbol -> loginInputEngine?.insertSymbol(symbol) }
        }

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
                    btnSubmit.nextFocusDownId = R.id.btn_use_sim_number
                    btnDemo.nextFocusUpId = R.id.btn_use_sim_number
                } else {
                    btnUseSim?.visibility = View.GONE
                    btnSubmit.nextFocusDownId = R.id.btn_demo_mode
                    btnDemo.nextFocusUpId = R.id.btn_login_submit
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
                btnSubmit.nextFocusDownId = R.id.btn_demo_mode
                btnDemo.nextFocusUpId = R.id.btn_login_submit
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
                btnSubmit.nextFocusDownId = R.id.btn_demo_mode
                btnDemo.nextFocusUpId = R.id.btn_login_submit
                tvLabel.text = "2-Step Verification Password:"
                etLoginInput?.hint = "Cloud Password"
                btnSubmit.text = "Submit Password"
                if (state.errorMessage != null) {
                    val errorMessage = "⚠️ Error: ${state.errorMessage}"
                    tvStatus.text = errorMessage
                    tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_error))
                    tvStatus.announceForAccessibility(errorMessage)
                } else {
                    tvStatus.text = if (state.hint.isNotBlank()) "Hint: ${state.hint}" else "Enter 2-Step Verification Password"
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

        softkeyManager.update(
            left = "Config",
            center = "Submit",
            right = "Demo",
            onLeft = { showSettingsScreen() },
            onCenter = { doSubmit() },
            onRight = {
                telegramManager.enableDemoMode()
                showChatListScreen()
            }
        )

        etLoginInput?.requestFocus()
    }

    private fun getDevicePhoneNumber(): String? {
        try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
            val hasPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED ||
                          (android.os.Build.VERSION.SDK_INT >= 26 && ContextCompat.checkSelfPermission(this, "android.permission.READ_PHONE_NUMBERS") == PackageManager.PERMISSION_GRANTED)
            if (hasPerm && tm != null) {
                @Suppress("MissingPermission")
                val raw = tm.line1Number
                if (!raw.isNullOrBlank()) {
                    val clean = org.telegram.PhoneFormat.PhoneFormat.stripExceptNumbers(raw, true)
                    if (!clean.isNullOrBlank()) {
                        return if (clean.startsWith("+")) clean else "+$clean"
                    }
                }
            }

            if (hasPerm && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP_MR1) {
                val sm = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? android.telephony.SubscriptionManager
                @Suppress("MissingPermission")
                sm?.activeSubscriptionInfoList?.forEach { sub ->
                    val num = sub.number
                    if (!num.isNullOrBlank()) {
                        val clean = org.telegram.PhoneFormat.PhoneFormat.stripExceptNumbers(num, true)
                        if (!clean.isNullOrBlank()) {
                            return if (clean.startsWith("+")) clean else "+$clean"
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            org.telegram.messenger.FileLog.e("getDevicePhoneNumber failed: ${e.message}")
        }
        return null
    }

    // =========================================================================
    // SCREEN 4: SETTINGS & CONFIGURATION
    // =========================================================================
    private fun showSettingsScreen() {
        currentScreen = Screen.SETTINGS
        mainContainer.removeAllViews()

        val view = LayoutInflater.from(this).inflate(R.layout.view_settings, mainContainer, true)

        val etId = view.findViewById<EditText>(R.id.et_api_id)
        val etHash = view.findViewById<EditText>(R.id.et_api_hash)
        val btnSave = view.findViewById<Button>(R.id.btn_save_config)
        val btnLogout = view.findViewById<Button>(R.id.btn_logout)
        val btnBack = view.findViewById<Button>(R.id.btn_back_to_chats)

        etId.setText(telegramManager.apiId.toString())
        etHash.setText(telegramManager.apiHash)

        val doSave = {
            val idStr = etId.text.toString().trim()
            val hashStr = etHash.text.toString().trim()
            if (idStr.isNotEmpty() && hashStr.isNotEmpty()) {
                telegramManager.apiId = idStr.toIntOrNull() ?: telegramManager.apiId
                telegramManager.apiHash = hashStr
                showToast("Saved! Reconnecting...")
                telegramManager.start()
                showChatListScreen()
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
        if (keyCode == KeyEvent.KEYCODE_SOFT_LEFT || keyCode == KeyEvent.KEYCODE_MENU ||
            keyCode == 132 /* F2 */ || keyCode == 60 || keyCode == 228) {
            if (softkeyManager.handleKeyEvent(keyCode, event)) return true
        }

        // Back key and Top Right hardware softkey handling
        if ((keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_SOFT_RIGHT ||
                keyCode == 229 || keyCode == 230 || keyCode == 61 || keyCode == 158) && event.action == KeyEvent.ACTION_DOWN) {
            if (voiceRecorder.isRecording()) {
                activeChat?.let { cancelVoiceRecordingUI(it) }
                return true
            }
            if (currentScreen == Screen.LOGIN) {
                telegramManager.enableDemoMode()
                showChatListScreen()
                return true
            }
            if (currentScreen == Screen.CONVERSATION) {
                showChatListScreen()
                return true
            }
            if (currentScreen == Screen.SETTINGS) {
                if (telegramManager.authState.value is AuthState.Ready) {
                    showChatListScreen()
                } else {
                    showLoginScreen(telegramManager.authState.value)
                }
                return true
            }
        }

        // Dedicated Call button: Start voice note recording or send in conversation
        if (keyCode == KeyEvent.KEYCODE_CALL && event.action == KeyEvent.ACTION_DOWN) {
            if (currentScreen == Screen.CONVERSATION) {
                activeChat?.let { chat ->
                    if (voiceRecorder.isRecording()) {
                        stopAndSendVoiceRecording(chat)
                    } else {
                        startVoiceRecordingFlow(chat)
                    }
                }
                return true
            }
        }

        // D-Pad Center / Enter Key
        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) && event.action == KeyEvent.ACTION_DOWN) {
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
                             keyCode == KeyEvent.KEYCODE_DEL
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

            // Left/Right D-pad keys cycle filter tabs
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                cycleTabs(-1)
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                cycleTabs(1)
                return true
            }
        }

        return super.dispatchKeyEvent(event)
    }

    private fun cycleTabs(direction: Int) {
        val tabOrder = listOf(null, ChatType.DIRECT, ChatType.GROUP, ChatType.CHANNEL)
        val currentIndex = tabOrder.indexOf(currentChatTypeFilter)
        val nextIndex = (currentIndex + direction + tabOrder.size) % tabOrder.size
        currentChatTypeFilter = tabOrder[nextIndex]

        val allChats = telegramManager.chats.value
        val filtered = if (currentChatTypeFilter == null) allChats else allChats.filter { it.chatType == currentChatTypeFilter }
        chatListAdapter?.submitList(filtered)
        findViewById<View>(R.id.layout_empty_state)?.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE

        val tabAll = findViewById<TextView>(R.id.tab_all)
        val tabDirect = findViewById<TextView>(R.id.tab_direct)
        val tabGroups = findViewById<TextView>(R.id.tab_groups)
        val tabChannels = findViewById<TextView>(R.id.tab_channels)

        tabAll?.isSelected = (currentChatTypeFilter == null)
        tabDirect?.isSelected = (currentChatTypeFilter == ChatType.DIRECT)
        tabGroups?.isSelected = (currentChatTypeFilter == ChatType.GROUP)
        tabChannels?.isSelected = (currentChatTypeFilter == ChatType.CHANNEL)
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
        if (softkeyManager.handleBack()) {
            return
        }
        super.onBackPressed()
    }
}
