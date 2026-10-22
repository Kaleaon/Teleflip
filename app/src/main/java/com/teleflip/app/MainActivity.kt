package com.teleflip.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.teleflip.app.export.ExportFormat
import com.teleflip.app.input.KeypadInputEngine
import com.teleflip.app.location.LocationHelper
import com.teleflip.app.media.VoicePlayer
import com.teleflip.app.media.VoiceRecorder
import com.teleflip.app.model.ActiveCall
import com.teleflip.app.model.CallState
import com.teleflip.app.model.ChatModel
import com.teleflip.app.model.ChatType
import com.teleflip.app.model.MediaType
import com.teleflip.app.model.MessageModel
import com.teleflip.app.model.MessageStatus
import com.teleflip.app.model.SavedGifItem
import com.teleflip.app.model.StickerSetModel
import com.teleflip.app.notifications.NotificationHelper
import com.teleflip.app.notifications.TeleFlipNotificationService
import com.teleflip.app.security.PasscodeManager
import com.teleflip.app.storage.ChatExportProgress
import com.teleflip.app.storage.ExportStage
import com.teleflip.app.storage.StorageLocationType
import com.teleflip.app.storage.StorageManager
import com.teleflip.app.telegram.AuthState
import com.teleflip.app.telegram.TelegramManager
import com.teleflip.app.ui.ChatExportDialog
import com.teleflip.app.ui.ChatExportProgressDialog
import com.teleflip.app.ui.ChatListAdapter
import com.teleflip.app.ui.MediaViewerDialog
import com.teleflip.app.ui.MenuOption
import com.teleflip.app.ui.MessageAdapter
import com.teleflip.app.ui.NewChatDialog
import com.teleflip.app.ui.OptionsMenuDialog
import com.teleflip.app.ui.PollDialog
import com.teleflip.app.ui.SoftkeyManager
import com.teleflip.app.ui.SoftkeyUtils
import com.teleflip.app.ui.SymbolsDialog
import com.teleflip.app.ui.TelegramFeatureMenus
import com.teleflip.app.ui.loadCircularAvatarOrFallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

enum class Screen {
    LOGIN,
    CHAT_LIST,
    CONVERSATION,
    SETTINGS,
    PROFILE,
    IN_CALL,
    PASSCODE_LOCK,
}

class MainActivity : AppCompatActivity() {
    private lateinit var mainContainer: FrameLayout
    private lateinit var softkeyManager: SoftkeyManager
    internal lateinit var telegramManager: TelegramManager
    private lateinit var voiceRecorder: VoiceRecorder
    private lateinit var voicePlayer: VoicePlayer
    private lateinit var locationHelper: LocationHelper
    private val featureMenus by lazy {
        TelegramFeatureMenus(this, telegramManager, { text, error ->
            showToast(
                text,
                error,
            )
        }, { chat -> openConversation(chat) }, { chat, messageId -> openConversation(chat, messageId) }, { chatId ->
            activeChat?.takeIf {
                it.id == chatId
            }?.let { sendLocationFlow(it) }
        })
    }

    internal var currentScreen: Screen = Screen.CHAT_LIST
    internal var activeChat: ChatModel? = null
    internal var pendingTargetChatId: Long = 0L
    private var pendingTargetMessageId: Long? = null
    private var callTimerJob: Job? = null
    private var callDurationSec: Int = 0
    private var pendingAlbumChatId: Long? = null
    private var pendingAlbumReplyId: Long = 0
    private var pendingAlbumCaption: String = ""
    private var pendingNavigationAction: (() -> Unit)? = null
    private var lastHandledCallId: Int? = null

    private fun navigateTo(
        targetScreen: Screen,
        action: () -> Unit,
    ) {
        if (currentScreen == Screen.IN_CALL && targetScreen != Screen.IN_CALL) {
            cleanupInCallAudio()
        }
        if (PasscodeManager.isLocked(this) && targetScreen != Screen.PASSCODE_LOCK) {
            pendingNavigationAction = action
            renderPasscodeUnlockScreen()
            return
        }
        if (targetScreen != Screen.PASSCODE_LOCK) {
            pendingNavigationAction = null
        }
        action()
    }

    // Chat List State
    private var currentChatTypeFilter: ChatType? = null
    private var isArchivedTabSelected: Boolean = false
    private var isFoldersTabSelected: Boolean = false
    private var activeFolderFilter: String? = null
    private var chatListAdapter: ChatListAdapter? = null
    private var rvChats: RecyclerView? = null
    private var tabDebounceJob: kotlinx.coroutines.Job? = null

    // Conversation State
    private var messageAdapter: MessageAdapter? = null
    private var rvMessages: RecyclerView? = null
    private var etComposeInput: EditText? = null
    private var composeInputEngine: KeypadInputEngine? = null
    private var draftSyncJob: Job? = null
    private var activeReplyMessage: MessageModel? = null
    private var activeEditMessage: MessageModel? = null
    private var layoutReplyEditBar: View? = null
    private var tvReplyEditPreview: TextView? = null
    private var layoutPinnedBanner: View? = null
    private var tvPinnedBannerText: TextView? = null

    // Voice Recording State
    private var layoutVoiceRecording: LinearLayout? = null
    private var layoutCompose: LinearLayout? = null
    private var tvRecordTimer: TextView? = null

    // Login State
    private var etLoginInput: EditText? = null
    private var loginInputEngine: KeypadInputEngine? = null

    // Passcode Lock State
    internal var passcodeEntry: String = ""
    private var tvPasscodeDots: TextView? = null
    private var tvPasscodeError: TextView? = null

    companion object {
        private const val REQ_PICK_STICKER = 1010
        private const val REQ_CALL_AUDIO_PERM = 1015
        private const val REQ_AUDIO_PERM = 1001
        private const val REQ_LOC_PERM = 1002
        private const val REQ_PHONE_PERM = 1003
        private const val REQ_PICK_ALBUM = 1016
        private const val REQ_PICK_PHOTO = 1004
        private const val REQ_PICK_DOCUMENT = 1005
        private const val REQ_PICK_VIDEO = 1006
        private const val BYTES_PER_MB = 1024 * 1024L
        private const val MUTE_1_HOUR_SEC = 3600
        private const val MUTE_8_HOURS_SEC = 28800
        private const val MUTE_2_DAYS_SEC = 172800
        private const val MUTE_FOREVER_SEC = Int.MAX_VALUE
        private const val PASSCODE_PIN_LENGTH = 4

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
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_NUMBERS) != PackageManager.PERMISSION_GRANTED
        ) {
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

        onBackPressedDispatcher.addCallback(
            this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (voiceRecorder.isRecording()) {
                        activeChat?.let { cancelVoiceRecordingUI(it) }
                        return
                    }
                    if (currentScreen == Screen.CONVERSATION && (activeReplyMessage != null || activeEditMessage != null)) {
                        clearReplyOrEditMode()
                        return
                    }
                    when (currentScreen) {
                        Screen.PASSCODE_LOCK -> finish()
                        Screen.CONVERSATION -> showChatListScreen()
                        Screen.PROFILE -> {
                            if (activeChat != null) {
                                openConversation(activeChat!!)
                            } else {
                                showChatListScreen()
                            }
                        }
                        Screen.IN_CALL -> {
                            minimizeCall()
                        }
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
            },
        )

        telegramManager = TelegramManager.getInstance(this)
        voiceRecorder = VoiceRecorder(this)
        voicePlayer = VoicePlayer(this)
        locationHelper = LocationHelper(this)

        NotificationHelper.ensureChannels(this)
        if (telegramManager.backgroundPushEnabled) {
            TeleFlipNotificationService.startOrStop(this, true)
        }

        observeTelegramState()

        telegramManager.start()

        pendingTargetChatId = intent?.getLongExtra(NotificationHelper.EXTRA_OPEN_CHAT_ID, 0L) ?: 0L

        if (PasscodeManager.isLocked(this)) {
            showPasscodeUnlockScreen()
        } else if (pendingTargetChatId != 0L) {
            val targetId = pendingTargetChatId
            pendingTargetChatId = 0L
            val targetChat =
                telegramManager.chats.value.find { it.id == targetId }
                    ?: ChatModel(
                        id = targetId,
                        title = "Chat #$targetId",
                        lastMessage = "",
                        time = "",
                        unreadCount = 0,
                        chatType = ChatType.DIRECT,
                        avatarLetters = "TG",
                    )
            openConversation(targetChat)
        }
    }

    override fun onStart() {
        super.onStart()
        PasscodeManager.onAppForegrounded(this)
        if (PasscodeManager.isLocked(this) && currentScreen != Screen.PASSCODE_LOCK) {
            val targetScreen = currentScreen
            val restorationAction: () -> Unit =
                when (targetScreen) {
                    Screen.CONVERSATION -> {
                        activeChat?.let { chat -> { renderConversation(chat, pendingTargetMessageId) } }
                            ?: { renderChatListScreen() }
                    }
                    Screen.PROFILE -> {
                        activeChat?.let { chat -> { renderProfileScreen(chat) } }
                            ?: { renderChatListScreen() }
                    }
                    Screen.SETTINGS -> {
                        { renderSettingsScreen() }
                    }
                    Screen.LOGIN -> {
                        { renderLoginScreen(telegramManager.authState.value) }
                    }
                    else -> {
                        { renderChatListScreen() }
                    }
                }
            navigateTo(targetScreen, restorationAction)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val targetOpenChatId = intent.getLongExtra(NotificationHelper.EXTRA_OPEN_CHAT_ID, 0L)
        if (targetOpenChatId != 0L) {
            pendingTargetChatId = targetOpenChatId
            if (!PasscodeManager.isLocked(this) && telegramManager.authState.value is AuthState.Ready) {
                val targetChat =
                    telegramManager.chats.value.find { it.id == targetOpenChatId }
                        ?: ChatModel(
                            id = targetOpenChatId,
                            title = "Chat #$targetOpenChatId",
                            lastMessage = "",
                            time = "",
                            unreadCount = 0,
                            chatType = ChatType.DIRECT,
                            avatarLetters = "TG",
                        )
                pendingTargetChatId = 0L
                openConversation(targetChat)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        cancelActiveVoiceOperations()
        PasscodeManager.onAppBackgrounded()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelActiveVoiceOperations()
        voiceRecorder.cancelRecording()
        voicePlayer.stop()
        cleanupInCallAudio()
        if (!telegramManager.backgroundPushEnabled && !telegramManager.hasOngoingCall) {
            telegramManager.stop()
        }
    }

    private fun observeTelegramState() {
        lifecycleScope.launch {
            telegramManager.authState.collectLatest { state ->
                when (state) {
                    is AuthState.Initial, is AuthState.WaitParameters -> {}
                    is AuthState.WaitPhoneNumber,
                    is AuthState.WaitOtherDeviceConfirmation,
                    is AuthState.WaitCode,
                    is AuthState.WaitPassword,
                    is AuthState.WaitPasswordRecoveryCode,
                    is AuthState.Error,
                    -> {
                        if (currentScreen != Screen.SETTINGS) {
                            showLoginScreen(state)
                        }
                    }
                    is AuthState.Ready -> {
                        if (PasscodeManager.isLocked(this@MainActivity)) {
                            // Locked behind passcode screen
                        } else if (currentScreen == Screen.LOGIN || mainContainer.childCount == 0) {
                            if (pendingTargetChatId != 0L) {
                                val targetId = pendingTargetChatId
                                pendingTargetChatId = 0L
                                val targetChat =
                                    telegramManager.chats.value.find { it.id == targetId }
                                        ?: ChatModel(
                                            id = targetId,
                                            title = "Chat #$targetId",
                                            lastMessage = "",
                                            time = "",
                                            unreadCount = 0,
                                            chatType = ChatType.DIRECT,
                                            avatarLetters = "TG",
                                        )
                                openConversation(targetChat)
                            } else {
                                showChatListScreen()
                            }
                        }
                    }
                }
            }
        }

        lifecycleScope.launch {
            telegramManager.operationError.collectLatest { error ->
                if (error != null) {
                    showToast(error, isError = true)
                    telegramManager.clearOperationError()
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
            telegramManager.chatFolders.collectLatest { folders ->
                if (currentScreen == Screen.CHAT_LIST) {
                    val view = mainContainer.getChildAt(0)
                    if (view != null) {
                        renderChatFolderTabs(view, folders)
                    }
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
                    val filtered = filterChatsForCurrentTab(chats)
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
                        val rawList = map[chat.id] ?: emptyList()
                        val list =
                            if (chat.isForum && chat.activeTopicId != 0L) {
                                rawList.filter { it.messageThreadId == 0L || it.messageThreadId == chat.activeTopicId }
                            } else {
                                rawList
                            }
                        messageAdapter?.submitList(list)
                        updatePinnedMessageBanner(chat, list)
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

        lifecycleScope.launch {
            telegramManager.activeCall.collectLatest { call ->
                if (call != null && call.state != CallState.DISCARDED && call.state != CallState.ERROR) {
                    if (currentScreen == Screen.IN_CALL) {
                        updateInCallUI(call)
                    } else if (lastHandledCallId != call.callId) {
                        lastHandledCallId = call.callId
                        showInCallScreen(call)
                    } else {
                        updateActiveCallOverlay()
                    }
                } else {
                    lastHandledCallId = null
                    cleanupInCallAudio()
                    updateActiveCallOverlay()
                    if (currentScreen == Screen.IN_CALL) {
                        if (activeChat != null) {
                            openConversation(activeChat!!)
                        } else {
                            showChatListScreen()
                        }
                    } else {
                        restoreCurrentScreenSoftkeys()
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
        navigateTo(Screen.CHAT_LIST) {
            renderChatListScreen()
        }
    }

    private fun renderChatListScreen() {
        commitActiveInputEngines()
        draftSyncJob?.cancel()
        draftSyncJob = null
        if (currentScreen == Screen.CONVERSATION && activeEditMessage == null) {
            activeChat?.let { chat ->
                val currentDraft = etComposeInput?.text?.toString()?.trim().orEmpty()
                telegramManager.saveChatDraft(chat.id, currentDraft)
            }
        }
        activeReplyMessage = null
        activeEditMessage = null
        currentScreen = Screen.CHAT_LIST
        activeChat?.let { telegramManager.closeChat(it.id) }
        activeChat = null
        voicePlayer.stop()
        mainContainer.removeAllViews()

        val view = LayoutInflater.from(this).inflate(R.layout.view_chat_list, mainContainer, true)
        findViewById<TextView>(R.id.tv_connection_status)?.text = telegramManager.connectionState.value

        rvChats = view.findViewById(R.id.rv_chats)
        rvChats?.layoutManager = LinearLayoutManager(this)
        chatListAdapter =
            ChatListAdapter { chat ->
                openConversation(chat)
            }
        rvChats?.adapter = chatListAdapter

        // Lazy loading / pagination on scroll
        rvChats?.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(
                    recyclerView: RecyclerView,
                    dx: Int,
                    dy: Int,
                ) {
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
            },
        )

        setupTabs(view)
        renderChatFolderTabs(view, telegramManager.chatFolders.value)

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
            onRight = { finish() },
        )

        rvChats?.post {
            if ((chatListAdapter?.itemCount ?: 0) > 0) {
                rvChats?.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
            } else {
                view.findViewById<View>(R.id.tab_all)?.requestFocus()
            }
        }

        applyChatFilterAndDebounce(currentChatTypeFilter)
        updateActiveCallOverlay()
    }

    private fun isTabViewFocused(): Boolean {
        val focusId = currentFocus?.id ?: return false
        if (focusId == R.id.tab_all || focusId == R.id.tab_direct ||
            focusId == R.id.tab_groups || focusId == R.id.tab_channels ||
            focusId == R.id.tab_bots || focusId == R.id.tab_folders ||
            focusId == R.id.tab_archived
        ) {
            return true
        }

        return currentFocus?.tag == "dynamic_folder_tab"
    }

    private fun filterChatsForCurrentTab(allChats: List<ChatModel>): List<ChatModel> {
        return when {
            isArchivedTabSelected -> allChats.filter { it.isArchived }
            isFoldersTabSelected -> {
                val targetFolder = activeFolderFilter
                if (!targetFolder.isNullOrBlank()) {
                    val folderModel = telegramManager.chatFolders.value.find { it.title.equals(targetFolder, ignoreCase = true) }
                    if (folderModel != null && folderModel.includedChatIds.isNotEmpty()) {
                        allChats.filter {
                            !it.isArchived &&
                                (folderModel.includedChatIds.contains(it.id) || it.folderName.equals(targetFolder, ignoreCase = true))
                        }
                    } else {
                        allChats.filter { !it.isArchived && it.folderName.equals(targetFolder, ignoreCase = true) }
                    }
                } else {
                    allChats.filter { !it.isArchived && it.folderName.isNotBlank() }
                }
            }
            currentChatTypeFilter != null -> allChats.filter { !it.isArchived && it.chatType == currentChatTypeFilter }
            else -> allChats.filter { !it.isArchived }
        }
    }

    private fun applyChatFilterAndDebounce(
        filter: ChatType?,
        archivedMode: Boolean = false,
        foldersMode: Boolean = false,
    ) {
        tabDebounceJob?.cancel()
        currentChatTypeFilter = filter
        isArchivedTabSelected = archivedMode
        isFoldersTabSelected = foldersMode

        val tabAll = findViewById<TextView>(R.id.tab_all)
        val tabDirect = findViewById<TextView>(R.id.tab_direct)
        val tabGroups = findViewById<TextView>(R.id.tab_groups)
        val tabChannels = findViewById<TextView>(R.id.tab_channels)
        val tabBots = findViewById<TextView>(R.id.tab_bots)
        val tabFolders = findViewById<TextView>(R.id.tab_folders)
        val tabArchived = findViewById<TextView>(R.id.tab_archived)

        tabAll?.isSelected = (filter == null && !archivedMode && !foldersMode)
        tabDirect?.isSelected = (filter == ChatType.DIRECT && !archivedMode && !foldersMode)
        tabGroups?.isSelected = (filter == ChatType.GROUP && !archivedMode && !foldersMode)
        tabChannels?.isSelected = (filter == ChatType.CHANNEL && !archivedMode && !foldersMode)
        tabBots?.isSelected = (filter == ChatType.BOT && !archivedMode && !foldersMode)
        tabFolders?.isSelected = (foldersMode && activeFolderFilter.isNullOrBlank())
        tabArchived?.isSelected = archivedMode

        val layoutTabs = findViewById<LinearLayout>(R.id.layout_tabs)
        if (layoutTabs != null) {
            for (i in 0 until layoutTabs.childCount) {
                val child = layoutTabs.getChildAt(i)
                if (child.tag == "dynamic_folder_tab" && child is TextView) {
                    val folderTitle = child.text.toString().removePrefix("📁 ").trim()
                    child.isSelected = (foldersMode && activeFolderFilter.equals(folderTitle, ignoreCase = true))
                }
            }
        }

        tabFolders?.text = if (!activeFolderFilter.isNullOrBlank()) "📁 $activeFolderFilter" else "📁 Folders"

        val allChats = telegramManager.chats.value
        val filtered = filterChatsForCurrentTab(allChats)
        chatListAdapter?.submitList(filtered)

        val emptyView = findViewById<View>(R.id.layout_empty_state)
        val progressView = findViewById<View>(R.id.progress_chats)

        if (filtered.isNotEmpty()) {
            progressView?.visibility = View.GONE
            emptyView?.visibility = View.GONE
        } else {
            progressView?.visibility = View.VISIBLE
            emptyView?.visibility = View.GONE

            tabDebounceJob =
                lifecycleScope.launch {
                    kotlinx.coroutines.delay(200)
                    val reCheckedChats = telegramManager.chats.value
                    val reCheckedFiltered = filterChatsForCurrentTab(reCheckedChats)
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

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun renderChatFolderTabs(
        rootView: View,
        folders: List<com.teleflip.app.model.ChatFolderModel>,
    ) {
        val layoutTabs = rootView.findViewById<LinearLayout>(R.id.layout_tabs) ?: return
        val tabArchived = rootView.findViewById<View>(R.id.tab_archived) ?: return
        val tabFolders = rootView.findViewById<View>(R.id.tab_folders)

        for (i in layoutTabs.childCount - 1 downTo 0) {
            val child = layoutTabs.getChildAt(i)
            if (child.tag == "dynamic_folder_tab") {
                layoutTabs.removeViewAt(i)
            }
        }

        var insertIdx = layoutTabs.indexOfChild(tabArchived)
        if (insertIdx < 0) insertIdx = layoutTabs.childCount

        if (folders.isNotEmpty()) {
            tabFolders?.visibility = View.GONE
        } else {
            tabFolders?.visibility = View.VISIBLE
        }

        folders.forEach { folder ->
            val tabView =
                TextView(this).apply {
                    id = View.generateViewId()
                    tag = "dynamic_folder_tab"
                    text = "📁 ${folder.title}"
                    setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                    textSize = 13f
                    gravity = android.view.Gravity.CENTER
                    minWidth = dpToPx(48)
                    minHeight = dpToPx(48)
                    setPadding(dpToPx(12), dpToPx(4), dpToPx(12), dpToPx(4))
                    background = ContextCompat.getDrawable(context, R.drawable.bg_tab_indicator)
                    isFocusable = true
                    isFocusableInTouchMode = true

                    setOnClickListener {
                        activeFolderFilter = folder.title
                        applyChatFilterAndDebounce(null, foldersMode = true)
                    }

                    setOnFocusChangeListener { _, hasFocus ->
                        if (hasFocus) {
                            activeFolderFilter = folder.title
                            applyChatFilterAndDebounce(null, foldersMode = true)
                        }
                    }
                }

            val lp =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginEnd = dpToPx(6)
                }

            layoutTabs.addView(tabView, insertIdx, lp)
            insertIdx++
        }

        relinkTabFocusChain(layoutTabs)
    }

    private fun relinkTabFocusChain(layoutTabs: LinearLayout) {
        val visibleTabs = mutableListOf<View>()
        for (i in 0 until layoutTabs.childCount) {
            val child = layoutTabs.getChildAt(i)
            if (child.visibility == View.VISIBLE) {
                visibleTabs.add(child)
            }
        }
        if (visibleTabs.isEmpty()) return

        for (i in visibleTabs.indices) {
            val current = visibleTabs[i]
            val prev = visibleTabs[(i - 1 + visibleTabs.size) % visibleTabs.size]
            val next = visibleTabs[(i + 1) % visibleTabs.size]

            current.nextFocusLeftId = prev.id
            current.nextFocusRightId = next.id
            current.nextFocusDownId = R.id.rv_chats
        }
    }

    private fun setupTabs(rootView: View) {
        val tabAll = rootView.findViewById<TextView>(R.id.tab_all)
        val tabDirect = rootView.findViewById<TextView>(R.id.tab_direct)
        val tabGroups = rootView.findViewById<TextView>(R.id.tab_groups)
        val tabChannels = rootView.findViewById<TextView>(R.id.tab_channels)
        val tabBots = rootView.findViewById<TextView>(R.id.tab_bots)
        val tabFolders = rootView.findViewById<TextView>(R.id.tab_folders)
        val tabArchived = rootView.findViewById<TextView>(R.id.tab_archived)

        tabAll?.setOnClickListener {
            activeFolderFilter = null
            applyChatFilterAndDebounce(null)
        }
        tabDirect?.setOnClickListener {
            activeFolderFilter = null
            applyChatFilterAndDebounce(ChatType.DIRECT)
        }
        tabGroups?.setOnClickListener {
            activeFolderFilter = null
            applyChatFilterAndDebounce(ChatType.GROUP)
        }
        tabChannels?.setOnClickListener {
            activeFolderFilter = null
            applyChatFilterAndDebounce(ChatType.CHANNEL)
        }
        tabBots?.setOnClickListener {
            activeFolderFilter = null
            applyChatFilterAndDebounce(ChatType.BOT)
        }
        tabFolders?.setOnClickListener { showFolderFilterMenu() }
        tabArchived?.setOnClickListener {
            activeFolderFilter = null
            applyChatFilterAndDebounce(null, archivedMode = true)
        }

        tabAll?.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                activeFolderFilter = null
                applyChatFilterAndDebounce(null)
            }
        }
        tabDirect?.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                activeFolderFilter = null
                applyChatFilterAndDebounce(ChatType.DIRECT)
            }
        }
        tabGroups?.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                activeFolderFilter = null
                applyChatFilterAndDebounce(ChatType.GROUP)
            }
        }
        tabChannels?.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                activeFolderFilter = null
                applyChatFilterAndDebounce(ChatType.CHANNEL)
            }
        }
        tabBots?.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                activeFolderFilter = null
                applyChatFilterAndDebounce(ChatType.BOT)
            }
        }
        tabFolders?.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) applyChatFilterAndDebounce(null, foldersMode = true) }
        tabArchived?.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                activeFolderFilter = null
                applyChatFilterAndDebounce(null, archivedMode = true)
            }
        }
    }

    private fun showFolderFilterMenu() {
        val presetFolders = listOf("Work", "Personal", "Family", "News")
        val dynamicFolders = telegramManager.chatFolders.value.map { it.title }
        val allFolderNames = (dynamicFolders + presetFolders).distinct()

        val options = mutableListOf<MenuOption>()
        options.add(
            MenuOption(1, "📁 All Folder Chats") {
                activeFolderFilter = null
                applyChatFilterAndDebounce(null, foldersMode = true)
            },
        )
        allFolderNames.forEach { folderName ->
            options.add(
                MenuOption(options.size + 1, "📁 Folder: $folderName") {
                    activeFolderFilter = folderName
                    applyChatFilterAndDebounce(null, foldersMode = true)
                },
            )
        }
        OptionsMenuDialog(this, "Select Chat Folder", options).show()
    }

    private fun showAssignChatFolderMenu(chat: ChatModel) {
        val folders = listOf("Work", "Personal", "Family", "News", "VIP")
        val options = mutableListOf<MenuOption>()
        folders.forEachIndexed { idx, name ->
            val prefix = if (chat.folderName.equals(name, ignoreCase = true)) "✓ " else ""
            options.add(
                MenuOption(idx + 1, "$prefix📁 Move to $name") {
                    telegramManager.assignChatToFolder(chat.id, name)
                    showToast("Moved ${chat.title} to folder '$name'")
                },
            )
        }
        if (chat.folderName.isNotBlank()) {
            options.add(
                MenuOption(options.size + 1, "✕ Remove from '${chat.folderName}'") {
                    telegramManager.assignChatToFolder(chat.id, "")
                    showToast("Removed ${chat.title} from folder")
                },
            )
        }
        OptionsMenuDialog(this, "Chat Folder for ${chat.title}", options).show()
    }

    private fun showToast(
        msg: String,
        isError: Boolean = false,
    ) {
        com.teleflip.app.report.AdbReporter.reportPopup("UI_Popup", msg, isError)
        Toast.makeText(this, msg, if (isError) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    private var activeDialogCount = 0
    private var savedFocusView: View? = null

    fun onDialogShown() {
        if (activeDialogCount == 0) {
            savedFocusView = currentFocus ?: window?.decorView?.findFocus()
        }
        activeDialogCount++
        softkeyManager.setSoftkeyBarVisible(false)
        updateActiveCallOverlay()
    }

    fun onDialogDismissed() {
        activeDialogCount--
        if (activeDialogCount <= 0) {
            activeDialogCount = 0
            softkeyManager.setSoftkeyBarVisible(true)
            val targetView = savedFocusView
            savedFocusView = null
            targetView?.post {
                if (targetView.isAttachedToWindow) {
                    targetView.requestFocus()
                    targetView.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED)
                }
            }
        }
        updateActiveCallOverlay()
    }

    private fun getFocusedOrTopChat(): ChatModel? {
        val focusedView = currentFocus
        val rv = rvChats
        if (focusedView != null && rv != null) {
            val itemView = rv.findContainingItemView(focusedView)
            if (itemView != null) {
                val pos = rv.getChildAdapterPosition(itemView)
                if (pos != RecyclerView.NO_POSITION) {
                    return chatListAdapter?.getItemAt(pos)
                }
            }
        }
        return chatListAdapter?.getItemAt(0)
    }

    private fun showNewChatDialog() {
        commitActiveInputEngines()
        NewChatDialog(
            context = this,
            timeoutMs = telegramManager.keypadTimeoutMs,
            onStartChatByQuery = { query ->
                telegramManager.resolveAndCreateChat(query) { chat, err ->
                    if (chat != null) {
                        openConversation(chat)
                    } else {
                        showToast(err ?: "Could not open chat", isError = true)
                    }
                }
            },
            onOpenSavedMessages = {
                telegramManager.openSavedMessages { savedChat, error ->
                    if (savedChat != null) openConversation(savedChat) else showToast(error ?: "Could not open Saved Messages", isError = true)
                }
            },
            onCreateGroup = { title ->
                telegramManager.createGroupOrChannel(title, isChannel = false) { groupChat, err ->
                    if (groupChat != null) {
                        showToast("Created group: ${groupChat.title}")
                        openConversation(groupChat)
                    } else {
                        showToast(err ?: "Could not create group", isError = true)
                    }
                }
            },
            onCreateChannel = { title ->
                telegramManager.createGroupOrChannel(title, isChannel = true) { channelChat, err ->
                    if (channelChat != null) {
                        showToast("Created channel: ${channelChat.title}")
                        openConversation(channelChat)
                    } else {
                        showToast(err ?: "Could not create channel", isError = true)
                    }
                }
            },
            onStartSecretChat = { query ->
                telegramManager.startSecretChat(query) { secretChat, err ->
                    if (secretChat != null) {
                        showToast("🔒 Secret Chat started with ${secretChat.title}")
                        openConversation(secretChat)
                    } else {
                        showToast(err ?: "Could not start Secret Chat", isError = true)
                    }
                }
            },
        ).show()
    }

    private fun showChatListOptionsMenu() {
        commitActiveInputEngines()
        val focusedChat = getFocusedOrTopChat()
        val options = mutableListOf<MenuOption>()

        options.add(
            MenuOption(options.size + 1, "🔍 Search (Chats, Contacts, Messages)") {
                showSearchDialog()
            },
        )
        options.add(
            MenuOption(options.size + 1, "➕ New Chat / Group / Channel") {
                showNewChatDialog()
            },
        )
        options.add(
            MenuOption(options.size + 1, "⭐ Saved Messages") {
                telegramManager.openSavedMessages { savedChat, error ->
                    if (savedChat != null) openConversation(savedChat) else showToast(error ?: "Could not open Saved Messages", isError = true)
                }
            },
        )
        options.add(
            MenuOption(options.size + 1, "👥 Telegram Contacts") {
                showContactsMenu()
            },
        )

        if (focusedChat != null) {
            val pinLabel = if (focusedChat.isPinned) "📌 Unpin ${focusedChat.title}" else "📌 Pin ${focusedChat.title}"
            options.add(
                MenuOption(options.size + 1, pinLabel) {
                    telegramManager.toggleChatPin(focusedChat.id, focusedChat.isPinned) { isNowPinned ->
                        showToast(if (isNowPinned) "Pinned ${focusedChat.title}" else "Unpinned ${focusedChat.title}")
                    }
                },
            )
            val muteLabel = if (focusedChat.isMuted) "🔔 Unmute ${focusedChat.title}" else "🔇 Mute Notifications..."
            options.add(
                MenuOption(options.size + 1, muteLabel) {
                    showChatMuteDialog(focusedChat.id, focusedChat.isMuted, focusedChat.title)
                },
            )
            val archiveLabel = if (focusedChat.isArchived) "📤 Unarchive ${focusedChat.title}" else "📦 Archive ${focusedChat.title}"
            options.add(
                MenuOption(options.size + 1, archiveLabel) {
                    telegramManager.toggleChatArchived(focusedChat.id, focusedChat.isArchived) { isNowArchived ->
                        showToast(if (isNowArchived) "Archived ${focusedChat.title}" else "Unarchived ${focusedChat.title}")
                    }
                },
            )
            options.add(
                MenuOption(options.size + 1, "📁 Assign ${focusedChat.title} to Folder...") {
                    showAssignChatFolderMenu(focusedChat)
                },
            )
            if (focusedChat.unreadCount > 0) {
                options.add(
                    MenuOption(options.size + 1, "✓✓ Mark ${focusedChat.title} Read") {
                        telegramManager.markChatAsRead(focusedChat.id)
                        showToast("Marked ${focusedChat.title} as read")
                    },
                )
            }
            options.add(
                MenuOption(options.size + 1, "📥 Export Chat History") {
                    showChatExportFormatDialog(focusedChat.id, focusedChat.title)
                },
            )
        }

        options.add(MenuOption(options.size + 1, "Open Telegram link / Join chat") { featureMenus.invite() })
        if (focusedChat != null) options.add(MenuOption(options.size + 1, if (focusedChat.isMarkedUnread) "Clear unread mark" else "Mark as unread") { featureMenus.unread(focusedChat) })
        options.add(
            MenuOption(options.size + 1, "🔄 Refresh Chats") {
                telegramManager.loadChats()
                showToast("Refreshing chats...")
            },
        )
        options.add(
            MenuOption(options.size + 1, "🩺 Diagnostics & Error Log") {
                com.teleflip.app.ui.DiagnosticsDialog(this).show()
            },
        )
        options.add(
            MenuOption(options.size + 1, "⚙️ Settings & API") {
                showSettingsScreen()
            },
        )
        options.add(
            MenuOption(options.size + 1, "🚪 Log Out") {
                telegramManager.logOut()
            },
        )
        OptionsMenuDialog(this, "Chat Options", options).show()
    }

    private fun showChatExportFormatDialog(
        chatId: Long,
        chatTitle: String,
    ) {
        commitActiveInputEngines()
        val options =
            listOf(
                MenuOption(1, "📄 JSON Format (.json)") {
                    startChatExport(chatId, chatTitle, ExportFormat.JSON)
                },
                MenuOption(2, "🌐 HTML Format (.html)") {
                    startChatExport(chatId, chatTitle, ExportFormat.HTML)
                },
                MenuOption(3, "📦 Both Formats (JSON & HTML)") {
                    startChatExport(chatId, chatTitle, ExportFormat.BOTH)
                },
            )
        OptionsMenuDialog(this, "Select Export Format", options).show()
    }

    private fun startChatExport(
        chatId: Long,
        chatTitle: String,
        format: ExportFormat,
    ) {
        ChatExportProgressDialog(
            context = this,
            scope = lifecycleScope,
            telegramManager = telegramManager,
            chatId = chatId,
            chatTitle = chatTitle,
            format = format,
            onCompleteCallback = { result ->
                result.onSuccess { files ->
                    val names = files.joinToString(", ") { it.name }
                    val dirPath = files.firstOrNull()?.parent ?: ""
                    showToast("Export completed! Saved $names to $dirPath")
                }.onFailure { err ->
                    if (err is java.util.concurrent.CancellationException) {
                        showToast("Chat export cancelled")
                    } else {
                        showToast(err.message ?: "Chat export failed", isError = true)
                    }
                }
            },
        ).show()
    }

    private fun showSearchDialog() {
        com.teleflip.app.ui.SearchDialog(
            context = this,
            telegramManager = telegramManager,
            onChatSelected = { chat ->
                openConversation(chat)
            },
            onMessageSelected = { chatId, messageId ->
                val chat =
                    telegramManager.chats.value.find { it.id == chatId }
                        ?: ChatModel(
                            id = chatId,
                            title = "Chat #$chatId",
                            lastMessage = "",
                            time = "",
                            unreadCount = 0,
                            chatType = ChatType.DIRECT,
                            avatarLetters = "TG",
                        )
                openConversation(chat, targetMessageId = messageId)
            },
        ).show()
    }

    // =========================================================================
    // SCREEN 2: CONVERSATION / CHAT
    // =========================================================================
    private fun openConversation(
        chat: ChatModel,
        targetMessageId: Long? = null,
    ) {
        navigateTo(Screen.CONVERSATION) {
            renderConversation(chat, targetMessageId)
        }
    }

    private fun renderConversation(
        chat: ChatModel,
        targetMessageId: Long? = null,
    ) {
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

        val tvConvTitle = view.findViewById<TextView>(R.id.tv_conv_title)
        tvConvTitle.text = chat.title
        val tvConvStarBadge = view.findViewById<TextView>(R.id.tv_conv_star_badge)
        tvConvStarBadge?.visibility = if (chat.isPremium) View.VISIBLE else View.GONE
        val subtitle = view.findViewById<TextView>(R.id.tv_conv_subtitle)
        subtitle.text =
            when {
                chat.isSecretChat -> {
                    val ttl = if (chat.selfDestructTimerSec > 0) " • ⏱️ ${chat.selfDestructTimerSec}s TTL" else ""
                    "🔒 Secret Chat (E2E)$ttl"
                }
                chat.isForum && chat.activeTopicName.isNotBlank() -> "🧵 Topic: ${chat.activeTopicName}"
                chat.chatType == ChatType.CHANNEL -> "Channel"
                chat.chatType == ChatType.GROUP -> "Group"
                chat.chatType == ChatType.BOT -> "Bot"
                else -> "online"
            }

        val ivConvAvatar = view.findViewById<ImageView>(R.id.iv_conv_avatar)
        val tvConvAvatar = view.findViewById<TextView>(R.id.tv_conv_avatar)
        ivConvAvatar?.contentDescription = com.teleflip.app.ui.ContentDescriptionFormatter.getAvatarDescription(this, chat.title)
        ivConvAvatar?.loadCircularAvatarOrFallback(chat.avatarPath, chat.avatarLetters, tvConvAvatar)

        val openProfileListener = View.OnClickListener { showProfileScreen(chat) }
        tvConvTitle.setOnClickListener(openProfileListener)
        ivConvAvatar?.setOnClickListener(openProfileListener)
        tvConvAvatar?.setOnClickListener(openProfileListener)

        rvMessages = view.findViewById(R.id.rv_messages)
        val layoutManager =
            LinearLayoutManager(this).apply {
                stackFromEnd = true
            }
        rvMessages?.layoutManager = layoutManager

        layoutVoiceRecording = view.findViewById(R.id.layout_voice_recording)
        layoutCompose = view.findViewById(R.id.layout_compose)
        tvRecordTimer = view.findViewById(R.id.tv_record_timer)
        layoutReplyEditBar = view.findViewById(R.id.layout_reply_edit_bar)
        tvReplyEditPreview = view.findViewById(R.id.tv_reply_edit_preview)
        layoutPinnedBanner = view.findViewById(R.id.layout_pinned_banner)
        tvPinnedBannerText = view.findViewById(R.id.tv_pinned_banner_text)
        view.findViewById<View>(R.id.btn_cancel_reply_edit)?.setOnClickListener {
            clearReplyOrEditMode()
        }
        activeReplyMessage = null
        activeEditMessage = null

        messageAdapter =
            MessageAdapter(
                onMessageFocused = { focusedMsg ->
                    updateSoftkeysForFocusedMessage(chat, focusedMsg)
                },
                onMessageAction = { clickedMsg ->
                    handleMessageActionClick(clickedMsg)
                },
                onTranscribeClick = { msg ->
                    handleTranscribeVoiceMessage(msg)
                },
                customEmojiResolver = { key ->
                    telegramManager.getCustomEmojiFilePath(key)
                },
                onReplyQuoteClick = { quoteMsg ->
                    val targetId = quoteMsg.replyToMessageId
                    if (targetId != 0L) {
                        val pos = messageAdapter?.getPositionForMessageId(targetId) ?: -1
                        if (pos >= 0) {
                            rvMessages?.scrollToPosition(pos)
                            rvMessages?.post {
                                rvMessages?.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
                            }
                        }
                    }
                },
                onReactionsClick = { rxnMsg ->
                    showReactionPickerDialog(rxnMsg)
                },
            )
        rvMessages?.adapter = messageAdapter

        val currentMsgs = telegramManager.messages.value[chat.id] ?: emptyList()
        messageAdapter?.submitList(currentMsgs)
        updatePinnedMessageBanner(chat, currentMsgs)
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
        rvMessages?.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(
                    recyclerView: RecyclerView,
                    dx: Int,
                    dy: Int,
                ) {
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
            },
        )

        etComposeInput = view.findViewById(R.id.et_message_input)
        val tvMode = view.findViewById<TextView>(R.id.tv_input_mode)
        val btnSend = view.findViewById<Button>(R.id.btn_send)

        composeInputEngine =
            KeypadInputEngine(
                editText = etComposeInput!!,
                modeIndicator = tvMode,
                timeoutMs = telegramManager.keypadTimeoutMs,
                onSymbolRequested = { showSymbolsDialog { symbol -> composeInputEngine?.insertSymbol(symbol) } },
            )

        val savedDraft = telegramManager.getChatDraft(chat.id)
        if (savedDraft.isNotBlank()) {
            etComposeInput?.setText(savedDraft)
            etComposeInput?.setSelection(savedDraft.length)
        }

        val doSend = {
            commitActiveInputEngines()
            draftSyncJob?.cancel()
            draftSyncJob = null
            val text = etComposeInput?.text?.toString()?.trim() ?: ""
            if (text.isNotEmpty()) {
                val editing = activeEditMessage
                val replying = activeReplyMessage
                if (editing != null) {
                    telegramManager.editMessageText(chat.id, editing.id, text) {
                        showToast("Message edited")
                    }
                    clearReplyOrEditMode()
                    etComposeInput?.setText("")
                } else if (replying != null) {
                    val preview = "${replying.senderName}: ${replying.text.take(35)}"
                    telegramManager.sendMessage(
                        chatId = chat.id,
                        text = text,
                        replyToMessageId = replying.id,
                        replyToPreview = preview,
                    )
                    clearReplyOrEditMode()
                    etComposeInput?.setText("")
                } else {
                    telegramManager.sendMessage(chat.id, text)
                    etComposeInput?.setText("")
                }
            }
        }

        btnSend.setOnClickListener { doSend() }
        btnSend.setOnLongClickListener {
            showSendOptionsMenu(chat)
            true
        }

        etComposeInput?.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {}

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int,
                ) {
                    if (!s.isNullOrEmpty()) {
                        telegramManager.sendTypingAction(chat.id)
                    }
                }

                override fun afterTextChanged(s: android.text.Editable?) {
                    draftSyncJob?.cancel()
                    draftSyncJob =
                        lifecycleScope.launch {
                            delay(500L)
                            val text = s?.toString()?.trim().orEmpty()
                            telegramManager.saveChatDraft(chat.id, text)
                        }
                }
            },
        )

        etComposeInput?.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && !voiceRecorder.isRecording()) {
                softkeyManager.update(
                    left = "Attach",
                    center = "Send",
                    right = "Back",
                    onLeft = { showAttachMenu(chat) },
                    onCenter = { doSend() },
                    onRight = { showChatListScreen() },
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
            onRight = { showChatListScreen() },
        )

        etComposeInput?.requestFocus()
        updateActiveCallOverlay()
    }

    private var lastResendTimestamp = 0L

    private fun handleResendMessage(msg: MessageModel) {
        val now = System.currentTimeMillis()
        if (now - lastResendTimestamp < 1000L) {
            return
        }
        lastResendTimestamp = now

        val isOffline =
            telegramManager.connectionState.value == "Offline" || telegramManager.connectionState.value == "Waiting for network"

        if (isOffline) {
            telegramManager.updateMessageStatus(msg.chatId, msg.id, MessageStatus.FAILED)
            showToast("Offline: Unable to resend message", isError = true)
            return
        }

        showToast("Retrying message delivery...")
        telegramManager.resendMessage(msg.chatId, msg.id)
    }

    private fun updateSoftkeysForFocusedMessage(
        chat: ChatModel,
        msg: MessageModel,
    ) {
        if (msg.isOutbound && msg.status == MessageStatus.FAILED) {
            softkeyManager.update(
                left = "Options",
                center = "Retry",
                right = "Back",
                onLeft = { showMessageOptionsMenu(msg) },
                onCenter = { handleResendMessage(msg) },
                onRight = { showChatListScreen() },
            )
            return
        }

        when (msg.mediaType) {
            MediaType.CONTACT -> {
                softkeyManager.update(
                    left = "Options",
                    center = "Contact",
                    right = "Back",
                    onLeft = { showMessageOptionsMenu(msg) },
                    onCenter = { featureMenus.contact(msg) },
                    onRight = { showChatListScreen() },
                )
            }
            MediaType.VOICE, MediaType.AUDIO -> {
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
                        onRight = { showChatListScreen() },
                    )
                } else {
                    softkeyManager.update(
                        left = "Options",
                        center = "Play",
                        right = "Back",
                        onLeft = { showMessageOptionsMenu(msg) },
                        onCenter = { handlePlayVoiceMessage(msg) },
                        onRight = { showChatListScreen() },
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
                    onRight = { showChatListScreen() },
                )
            }
            MediaType.PHOTO, MediaType.STICKER -> {
                softkeyManager.update(
                    left = "Options",
                    center = "Zoom/View",
                    right = "Back",
                    onLeft = { showMessageOptionsMenu(msg) },
                    onCenter = { openMediaViewer(msg) },
                    onRight = { showChatListScreen() },
                )
            }
            MediaType.VIDEO, MediaType.ANIMATION -> {
                softkeyManager.update(
                    left = "Options",
                    center = "Play",
                    right = "Back",
                    onLeft = { showMessageOptionsMenu(msg) },
                    onCenter = { openMediaViewer(msg) },
                    onRight = { showChatListScreen() },
                )
            }
            MediaType.DOCUMENT -> {
                softkeyManager.update(
                    left = "Options",
                    center = "Open",
                    right = "Back",
                    onLeft = { showMessageOptionsMenu(msg) },
                    onCenter = { openDocument(msg) },
                    onRight = { showChatListScreen() },
                )
            }
            MediaType.POLL -> {
                softkeyManager.update(
                    left = "Options",
                    center = "Vote",
                    right = "Back",
                    onLeft = { showMessageOptionsMenu(msg) },
                    onCenter = { showPollVoteMenu(msg) },
                    onRight = { showChatListScreen() },
                )
            }
            MediaType.PAID_MEDIA -> {
                if (msg.isPaidMediaLocked) {
                    softkeyManager.update(
                        left = "Options",
                        center = "Refresh",
                        right = "Back",
                        onLeft = { showMessageOptionsMenu(msg) },
                        onCenter = { handlePaidMediaAction(msg) },
                        onRight = { showChatListScreen() },
                    )
                } else {
                    softkeyManager.update(
                        left = "Options",
                        center = "View",
                        right = "Back",
                        onLeft = { showMessageOptionsMenu(msg) },
                        onCenter = { openMediaViewer(msg) },
                        onRight = { showChatListScreen() },
                    )
                }
            }
            else -> {
                softkeyManager.update(
                    left = "Options",
                    center = "Compose",
                    right = "Back",
                    onLeft = { showMessageOptionsMenu(msg) },
                    onCenter = { etComposeInput?.requestFocus() },
                    onRight = { showChatListScreen() },
                )
            }
        }
        updateActiveCallOverlay()
    }

    private fun handleMessageActionClick(msg: MessageModel) {
        when (msg.mediaType) {
            MediaType.VOICE, MediaType.AUDIO -> handlePlayVoiceMessage(msg)
            MediaType.DOCUMENT -> openDocument(msg)
            MediaType.CONTACT -> featureMenus.contact(msg)
            MediaType.LOCATION -> LocationHelper.openInOrganicMaps(this, msg.latitude, msg.longitude)
            MediaType.PHOTO, MediaType.VIDEO, MediaType.ANIMATION, MediaType.STICKER -> openMediaViewer(msg)
            MediaType.POLL -> showPollVoteMenu(msg)
            MediaType.PAID_MEDIA -> handlePaidMediaAction(msg)
            else -> showMessageOptionsMenu(msg)
        }
    }

    private fun handlePaidMediaAction(msg: MessageModel) {
        if (msg.isPaidMediaLocked) {
            showPaidMediaUnlockConfirmation(msg)
        } else {
            openMediaViewer(msg)
        }
    }

    private fun showPaidMediaUnlockConfirmation(msg: MessageModel) {
        commitActiveInputEngines()
        showToast("Checking Stars price…")
        telegramManager.quotePaidMedia(msg.chatId, msg.id) { quote, error ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (quote == null) {
                    showToast(error ?: "Unable to load payment", isError = true)
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Unlock paid media")
                        .setMessage("Pay ${quote.stars} Telegram Stars to unlock this media?")
                        .setPositiveButton("Pay ${quote.stars} Stars") { _, _ ->
                            showToast("Processing payment…")
                            telegramManager.purchasePaidMedia(quote) { paid, paymentError ->
                                runOnUiThread {
                                    showToast(if (paid) "Payment completed. Refreshing media…" else paymentError ?: "Payment failed", isError = !paid)
                                }
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
        }
    }

    private fun openMediaViewer(msg: MessageModel) {
        commitActiveInputEngines()
        MediaViewerDialog(
            context = this,
            message = msg,
            messageUpdates = telegramManager.messages,
            onSaveMedia = { targetMsg -> saveMessageToDevice(targetMsg) },
            onRequestDownload = { fileId ->
                telegramManager.downloadFilePriority(fileId)
                showToast("Prioritizing media download...")
            },
        ).show()
    }

    private fun showPollVoteMenu(msg: MessageModel) {
        commitActiveInputEngines()
        if (msg.pollIsClosed) {
            showToast("This poll is closed")
            return
        }
        val options = mutableListOf<MenuOption>()
        msg.pollOptions.forEachIndexed { idx, opt ->
            val mark = if (opt.isChosen) "✓ " else ""
            options.add(
                MenuOption(idx + 1, "${mark}Vote: ${opt.text} (${opt.votePercentage}%)") {
                    telegramManager.votePoll(msg.chatId, msg.id, idx)
                    showToast("Voted for '${opt.text}'")
                },
            )
        }
        if (msg.isOutbound) {
            options.add(
                MenuOption(options.size + 1, "🛑 Close / Stop Poll") {
                    telegramManager.stopPoll(msg.chatId, msg.id)
                    showToast("Poll closed")
                },
            )
        }
        OptionsMenuDialog(this, msg.pollQuestion.ifBlank { "Poll Options" }, options).show()
    }

    private fun handleTranscribeVoiceMessage(msg: MessageModel) {
        showToast("Transcribing voice note...")
        telegramManager.recognizeSpeech(msg.chatId, msg.id) { success, errorMsg ->
            if (!success) {
                showToast(errorMsg ?: "Speech recognition failed", true)
            }
        }
    }

    private val pendingMediaActions = mutableSetOf<Pair<Long, Long>>()

    private fun withDownloadedMedia(
        msg: MessageModel,
        onReady: (MessageModel) -> Unit,
    ) {
        val existing = msg.mediaFilePath
        if (!existing.isNullOrBlank() && File(existing).isFile) {
            onReady(msg)
            return
        }
        if (msg.mediaFileId <= 0) {
            showToast("This attachment has no downloadable file", isError = true)
            return
        }
        val key = msg.chatId to msg.id
        if (!pendingMediaActions.add(key)) return
        showToast("Downloading attachment…")
        lifecycleScope.launch {
            try {
                val path = telegramManager.downloadFileSuspending(msg.mediaFileId)
                if (path != null) onReady(msg.copy(mediaFilePath = path)) else showToast("Download did not complete. Try again.", isError = true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                showToast(e.message ?: "Media download failed", isError = true)
            } finally {
                pendingMediaActions.remove(key)
            }
        }
    }

    private fun openDocument(msg: MessageModel) {
        withDownloadedMedia(msg) { ready ->
            try {
                val file = File(requireNotNull(ready.mediaFilePath))
                val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", file)
                val mimeType =
                    ready.mediaMimeType.ifBlank {
                        android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
                    }
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (e: Exception) {
                showToast("No app can open this attachment. Use Save to Device.", isError = true)
            }
        }
    }

    private fun handlePlayVoiceMessage(msg: MessageModel) {
        val path = msg.mediaFilePath
        if (path.isNullOrBlank() || !File(path).isFile) {
            withDownloadedMedia(msg) { ready ->
                if (activeChat?.id == ready.chatId) handlePlayVoiceMessage(ready)
            }
            return
        }

        if (voicePlayer.isPlayingPath(path)) {
            voicePlayer.stop()
            messageAdapter?.setPlayingMessageId(null)
            activeChat?.let { updateSoftkeysForFocusedMessage(it, msg) }
        } else {
            messageAdapter?.setPlayingMessageId(msg.id, voicePlayer.currentSpeed)
            val success =
                voicePlayer.play(
                    path = path,
                    onError = {
                        messageAdapter?.setPlayingMessageId(null)
                        activeChat?.let { updateSoftkeysForFocusedMessage(it, msg) }
                        showToast("Unable to play audio recording", true)
                    },
                    onCompletion = {
                        messageAdapter?.setPlayingMessageId(null)
                        activeChat?.let { updateSoftkeysForFocusedMessage(it, msg) }
                    },
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

        val started =
            voiceRecorder.startRecording { elapsedSec ->
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
            onRight = { cancelVoiceRecordingUI(chat) },
        )
    }

    private fun stopAndSendVoiceRecording(chat: ChatModel) {
        val recording = voiceRecorder.stopRecording()
        layoutVoiceRecording?.visibility = View.GONE
        layoutCompose?.visibility = View.VISIBLE

        if (recording != null && recording.file.exists()) {
            telegramManager.sendVoiceNote(chat.id, recording.file.absolutePath, recording.durationSeconds)
            showToast("Sending voice note (${recording.durationSeconds}s)…")
        } else {
            showToast("Recording was too short or empty", true)
        }

        restoreConversationSoftkeys(chat)
    }

    internal fun cancelActiveVoiceOperations(targetChat: ChatModel? = activeChat) {
        if (voiceRecorder.isRecording()) {
            voiceRecorder.cancelRecording()
            layoutVoiceRecording?.visibility = View.GONE
            layoutCompose?.visibility = View.VISIBLE
            (targetChat ?: activeChat)?.let { restoreConversationSoftkeys(it) }
        }
        voicePlayer.stop()
    }

    private fun cancelVoiceRecordingUI(chat: ChatModel) {
        cancelActiveVoiceOperations(chat)
        showToast("Recording discarded")
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
            onRight = { showChatListScreen() },
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
                REQ_LOC_PERM,
            )
            return
        }

        showToast("Acquiring GPS position from hardware...")

        locationHelper.fetchCurrentLocation(
            onSuccess = { locInfo ->
                telegramManager.sendLocation(chat.id, locInfo.latitude, locInfo.longitude, locInfo.accuracyMeters)
                showToast("Sending GPS location…")
            },
            onError = { errMsg ->
                showToast(errMsg, true)
            },
        )
    }

    private fun startReplyToMessage(msg: MessageModel) {
        activeReplyMessage = msg
        activeEditMessage = null
        layoutReplyEditBar?.visibility = View.VISIBLE
        val snippet = msg.text.ifBlank { msg.mediaCaption }.take(35)
        tvReplyEditPreview?.text = "↩ Reply to ${msg.senderName}: $snippet"
        etComposeInput?.requestFocus()
    }

    private fun startEditingMessage(msg: MessageModel) {
        activeEditMessage = msg
        activeReplyMessage = null
        layoutReplyEditBar?.visibility = View.VISIBLE
        tvReplyEditPreview?.text = "✏️ Editing: ${msg.text.take(35)}"
        etComposeInput?.setText(msg.text)
        etComposeInput?.setSelection(msg.text.length)
        etComposeInput?.requestFocus()
    }

    private fun clearReplyOrEditMode() {
        val wasEditing = activeEditMessage != null
        activeReplyMessage = null
        activeEditMessage = null
        layoutReplyEditBar?.visibility = View.GONE
        if (wasEditing) {
            etComposeInput?.setText("")
        }
        etComposeInput?.requestFocus()
    }

    private fun updatePinnedMessageBanner(
        chat: ChatModel,
        messagesList: List<MessageModel>,
    ) {
        val pinnedMsg =
            messagesList.find { it.isPinned }
                ?: if (chat.pinnedMessageId != 0L) messagesList.find { it.id == chat.pinnedMessageId } else null

        if (pinnedMsg != null) {
            layoutPinnedBanner?.visibility = View.VISIBLE
            val snippet = pinnedMsg.text.ifBlank { pinnedMsg.mediaCaption }.ifBlank { "Attachment" }
            tvPinnedBannerText?.text = "📌 Pinned: $snippet"
            layoutPinnedBanner?.setOnClickListener {
                val pos = messageAdapter?.getPositionForMessageId(pinnedMsg.id) ?: -1
                if (pos >= 0) {
                    rvMessages?.scrollToPosition(pos)
                    rvMessages?.post {
                        rvMessages?.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
                    }
                }
            }
        } else {
            layoutPinnedBanner?.visibility = View.GONE
        }
    }

    private fun showAttachMenu(chat: ChatModel) {
        commitActiveInputEngines()
        val options = mutableListOf<MenuOption>()
        if (chat.chatType == ChatType.DIRECT) {
            options.add(
                MenuOption(options.size + 1, "📞 Call Contact") {
                    initiateCallFlow(chat)
                },
            )
        }
        options.add(
            MenuOption(options.size + 1, "🎤 Record Voice Message (Push-to-Talk)") {
                startVoiceRecordingFlow(chat)
            },
        )
        options.add(
            MenuOption(options.size + 1, "📷 Send Photo / Image") {
                showSendMediaPicker(chat, isPhoto = true)
            },
        )
        options.add(
            MenuOption(options.size + 1, "📹 Send Video or GIF") {
                showSendVideoOrGifPicker(chat)
            },
        )
        options.add(
            MenuOption(options.size + 1, "📎 Send File / Document") {
                showSendMediaPicker(chat, isPhoto = false)
            },
        )
        options.add(
            MenuOption(options.size + 1, "📊 Create Telegram Poll") {
                showCreatePollDialog(chat)
            },
        )
        options.add(
            MenuOption(options.size + 1, "📍 Send Current GPS Location") {
                sendLocationFlow(chat)
            },
        )
        options.add(
            MenuOption(options.size + 1, "🎨 Send Sticker") {
                showStickerPickerMenu(chat)
            },
        )
        options.add(
            MenuOption(options.size + 1, "🎞️ Send GIF / Animation") {
                showGifPickerMenu(chat)
            },
        )
        if (chat.chatType == ChatType.BOT || chat.chatType == ChatType.GROUP) {
            options.add(
                MenuOption(options.size + 1, "🤖 Bot Commands") {
                    showBotCommandsMenu(chat)
                },
            )
        }
        options.add(
            MenuOption(options.size + 1, "Symbols & Emoji (*)") {
                showSymbolsDialog { sym -> composeInputEngine?.insertSymbol(sym) }
            },
        )
        options.add(
            MenuOption(options.size + 1, "Quick Replies") {
                showQuickRepliesMenu(chat)
            },
        )
        if (chat.chatType == ChatType.GROUP || chat.chatType == ChatType.CHANNEL) {
            options.add(
                MenuOption(options.size + 1, "🧵 Forum Topics / Threads") {
                    showForumTopicsMenu(chat)
                },
            )
            options.add(
                MenuOption(options.size + 1, "👥 Group Members & Admins") {
                    showGroupMembersMenu(chat)
                },
            )
        }
        if (chat.chatType == ChatType.DIRECT) {
            options.add(
                MenuOption(options.size + 1, "⏱️ Self-Destruct Timer") {
                    showSelfDestructTimerMenu(chat)
                },
            )
        }
        val isMuted = telegramManager.isChatMuted(chat.id)
        val muteLabel = if (isMuted) "🔔 Unmute Notifications" else "🔇 Mute Notifications..."
        options.add(
            MenuOption(options.size + 1, muteLabel) {
                showChatMuteDialog(chat.id, isMuted, chat.title)
            },
        )
        options.add(
            MenuOption(options.size + 1, "👤 View Profile / Details") {
                showProfileScreen(chat)
            },
        )
        options.add(
            MenuOption(options.size + 1, "📤 Send Options (Silent / Schedule)") {
                showSendOptionsMenu(chat)
            },
        )
        options.add(
            MenuOption(options.size + 1, "🔍 Search in This Chat") {
                showSearchInChatDialog(chat)
            },
        )
        options.add(
            MenuOption(options.size + 1, "📥 Import History from Cloud") {
                showImportCloudHistoryDialog(chat)
            },
        )
        options.add(
            MenuOption(options.size + 1, "💾 Download Chat to SD Card / Storage...") {
                showExportChatHistoryDialog(chat)
            },
        )
        options.add(
            MenuOption(options.size + 1, "🗑️ Clear Chat History") {
                showClearChatHistoryDialog(chat)
            },
        )
        options.add(
            MenuOption(options.size + 1, "📥 Export Chat History") {
                showChatExportFormatDialog(chat.id, chat.title)
            },
        )
        options.add(MenuOption(options.size + 1, "Send photo album (2–10 photos)") { pickPhotoAlbum(chat) })
        options.add(MenuOption(options.size + 1, "Share a contact") { featureMenus.contactPicker(chat.id) })
        options.add(MenuOption(options.size + 1, "Select / forward / delete messages") { featureMenus.selectMessages(chat) })
        options.add(MenuOption(options.size + 1, "Scheduled messages") { featureMenus.scheduled(chat) })
        options.add(
            MenuOption(options.size + 1, "Send formatted draft") {
                commitActiveInputEngines()
                if (activeEditMessage != null) {
                    showToast("Finish editing before sending a formatted message", true)
                } else {
                    val draft = etComposeInput?.text?.toString().orEmpty()
                    val reply = activeReplyMessage?.id ?: 0
                    featureMenus.formatting(chat.id, draft, reply) {
                        if (activeChat?.id == chat.id && etComposeInput?.text?.toString() == draft && (activeReplyMessage?.id ?: 0) == reply) {
                            etComposeInput?.setText("")
                            clearReplyOrEditMode()
                            telegramManager.saveChatDraft(chat.id, "")
                        }
                    }
                }
            },
        )
        if (chat.chatType == ChatType.GROUP || chat.chatType == ChatType.CHANNEL) options.add(MenuOption(options.size + 1, "Leave group / channel") { featureMenus.leave(chat) })
        OptionsMenuDialog(this, "Attach & Actions", options).show()
    }

    private fun dispatchOutboundMessageWithOptions(
        chat: ChatModel,
        disableNotification: Boolean = false,
        schedulingState: org.drinkless.tdlib.TdApi.MessageSchedulingState? = null,
    ) {
        commitActiveInputEngines()
        val text = etComposeInput?.text?.toString()?.trim() ?: ""
        if (text.isEmpty()) {
            showToast("Please enter a message first")
            return
        }
        val editing = activeEditMessage
        val replying = activeReplyMessage
        if (editing != null) {
            telegramManager.editMessageText(chat.id, editing.id, text) {
                showToast("Message edited")
            }
            clearReplyOrEditMode()
            etComposeInput?.setText("")
        } else if (replying != null) {
            val preview = "${replying.senderName}: ${replying.text.take(35)}"
            telegramManager.sendMessage(
                chatId = chat.id,
                text = text,
                replyToMessageId = replying.id,
                replyToPreview = preview,
                disableNotification = disableNotification,
                schedulingState = schedulingState,
            )
            clearReplyOrEditMode()
            etComposeInput?.setText("")
        } else {
            telegramManager.sendMessage(
                chatId = chat.id,
                text = text,
                disableNotification = disableNotification,
                schedulingState = schedulingState,
            )
            etComposeInput?.setText("")
        }

        if (disableNotification && schedulingState != null) {
            showToast("Message scheduled silently")
        } else if (disableNotification) {
            showToast("Sending message silently…")
        } else if (schedulingState != null) {
            showToast("Message scheduled")
        }
    }

    private fun showSendOptionsMenu(chat: ChatModel) {
        val options = mutableListOf<MenuOption>()
        options.add(
            MenuOption(1, "🔕 Send Silently") {
                dispatchOutboundMessageWithOptions(chat, disableNotification = true)
            },
        )
        options.add(
            MenuOption(2, "📅 Schedule Message") {
                showScheduleTimePickerMenu(chat)
            },
        )
        options.add(
            MenuOption(3, "✉️ Send Normally") {
                dispatchOutboundMessageWithOptions(chat, disableNotification = false)
            },
        )
        OptionsMenuDialog(this, "Send Options", options).show()
    }

    private fun showScheduleTimePickerMenu(chat: ChatModel) {
        val options = mutableListOf<MenuOption>()
        val nowSec = (System.currentTimeMillis() / 1000).toInt()

        options.add(
            MenuOption(1, "In 10 minutes") {
                val sendAtDate = nowSec + 10 * 60
                val schedulingState = org.drinkless.tdlib.TdApi.MessageSchedulingStateSendAtDate(sendAtDate, 0)
                dispatchOutboundMessageWithOptions(chat, schedulingState = schedulingState)
            },
        )
        options.add(
            MenuOption(2, "In 30 minutes") {
                val sendAtDate = nowSec + 30 * 60
                val schedulingState = org.drinkless.tdlib.TdApi.MessageSchedulingStateSendAtDate(sendAtDate, 0)
                dispatchOutboundMessageWithOptions(chat, schedulingState = schedulingState)
            },
        )
        options.add(
            MenuOption(3, "In 1 hour") {
                val sendAtDate = nowSec + 60 * 60
                val schedulingState = org.drinkless.tdlib.TdApi.MessageSchedulingStateSendAtDate(sendAtDate, 0)
                dispatchOutboundMessageWithOptions(chat, schedulingState = schedulingState)
            },
        )
        options.add(
            MenuOption(4, "Tomorrow morning (09:00 AM)") {
                val calendar = java.util.Calendar.getInstance()
                calendar.add(java.util.Calendar.DAY_OF_YEAR, 1)
                calendar.set(java.util.Calendar.HOUR_OF_DAY, 9)
                calendar.set(java.util.Calendar.MINUTE, 0)
                calendar.set(java.util.Calendar.SECOND, 0)
                val sendAtDate = (calendar.timeInMillis / 1000).toInt()
                val schedulingState = org.drinkless.tdlib.TdApi.MessageSchedulingStateSendAtDate(sendAtDate, 0)
                dispatchOutboundMessageWithOptions(chat, schedulingState = schedulingState)
            },
        )
        options.add(
            MenuOption(5, "Custom delay (minutes)") {
                showCustomScheduleDelayDialog(chat)
            },
        )
        OptionsMenuDialog(this, "Schedule Message", options).show()
    }

    private fun showCustomScheduleDelayDialog(chat: ChatModel) {
        com.teleflip.app.ui.KeypadTextDialog(this, "Schedule delay in minutes", numeric = true) { value ->
            val minutes = value.trim().toLongOrNull()
            val now = System.currentTimeMillis() / 1000
            if (minutes == null || minutes !in 1..525600 || now + minutes * 60 > Int.MAX_VALUE) {
                showToast("Choose 1–525600 minutes")
            } else {
                dispatchOutboundMessageWithOptions(chat, schedulingState = org.drinkless.tdlib.TdApi.MessageSchedulingStateSendAtDate((now + minutes * 60).toInt(), 0))
            }
        }.show()
    }

    private fun showCreatePollDialog(chat: ChatModel) {
        commitActiveInputEngines()
        PollDialog(
            context = this,
            timeoutMs = telegramManager.keypadTimeoutMs,
            onCreatePoll = { question, pollOptions, isAnonymous ->
                telegramManager.sendPoll(chat.id, question, pollOptions, isAnonymous)
                showToast("Poll published!")
            },
        ).show()
    }

    private fun showSendVideoOrGifPicker(chat: ChatModel) {
        val searchDirs =
            listOf(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            )
        val allowedExts = setOf("mp4", "mkv", "webm", "3gp", "gif")
        val recentFiles =
            searchDirs
                .filterNotNull()
                .filter { it.exists() && it.isDirectory }
                .flatMap { dir ->
                    dir.walkTopDown().maxDepth(2).filter { f ->
                        f.isFile && f.extension.lowercase() in allowedExts
                    }.toList()
                }
                .sortedByDescending { it.lastModified() }
                .take(7)

        val options = mutableListOf<MenuOption>()
        options.add(
            MenuOption(1, "📂 Browse System Videos / GIFs...") {
                try {
                    val intent =
                        Intent(Intent.ACTION_GET_CONTENT).apply {
                            type = "video/*"
                            addCategory(Intent.CATEGORY_OPENABLE)
                        }
                    startActivityForResult(intent, REQ_PICK_VIDEO)
                } catch (e: Exception) {
                    showToast("System video picker unavailable", isError = true)
                }
            },
        )
        val caption = etComposeInput?.text?.toString()?.trim().orEmpty()
        recentFiles.forEach { file ->
            val isGif = file.extension.equals("gif", ignoreCase = true)
            val icon = if (isGif) "🎞️" else "📹"
            options.add(
                MenuOption(options.size + 1, "$icon ${file.name}") {
                    if (isGif) {
                        telegramManager.sendAnimation(chat.id, file.absolutePath, caption)
                        showToast("Sending GIF: ${file.name}")
                    } else {
                        telegramManager.sendVideo(chat.id, file.absolutePath, 0, caption)
                        showToast("Sending video: ${file.name}")
                    }
                    if (caption.isNotEmpty()) {
                        etComposeInput?.setText("")
                    }
                },
            )
        }
        OptionsMenuDialog(this, "Send Video or GIF", options).show()
    }

    private fun showForumTopicsMenu(chat: ChatModel) {
        commitActiveInputEngines()
        telegramManager.loadForumTopics(chat.id) { rawTopics ->
            val topics = rawTopics.sortedByDescending { it.isPinned }
            val options = mutableListOf<MenuOption>()
            topics.forEach { topic ->
                val activeMark = if (chat.activeTopicId == topic.topicId) "✓ " else ""
                val pinBadge = if (topic.isPinned) "📌 " else ""
                options.add(
                    MenuOption(options.size + 1, "$activeMark$pinBadge${topic.iconEmoji} ${topic.name}") {
                        telegramManager.selectForumTopic(chat.id, topic.topicId, topic.name)
                        val updatedChat =
                            chat.copy(
                                isForum = true,
                                activeTopicId = topic.topicId,
                                activeTopicName = if (topic.topicId == 0L) "" else topic.name,
                            )
                        openConversation(updatedChat)
                    },
                )
                val pinLabel = if (topic.isPinned) "📌 Unpin '${topic.name}'" else "📌 Pin '${topic.name}'"
                options.add(
                    MenuOption(options.size + 1, pinLabel) {
                        telegramManager.toggleForumTopicPin(chat.id, topic.topicId, topic.isPinned) { isNowPinned, errorMsg ->
                            if (errorMsg != null) {
                                showToast(errorMsg)
                            } else {
                                val actionStr = if (isNowPinned) "Pinned" else "Unpinned"
                                showToast("$actionStr topic: ${topic.name}")
                                showForumTopicsMenu(chat)
                            }
                        }
                    },
                )
            }
            options.add(
                MenuOption(options.size + 1, "➕ Create New Topic Thread") {
                    val draftTitle = etComposeInput?.text?.toString()?.trim().orEmpty().ifBlank { "Discussion #${topics.size}" }
                    telegramManager.createForumTopic(chat.id, draftTitle) { created, error ->
                        if (created == null) {
                            showToast(error ?: "Could not create topic", isError = true)
                            return@createForumTopic
                        }
                        showToast("Created topic: ${created.name}")
                        val updatedChat = chat.copy(isForum = true, activeTopicId = created.topicId, activeTopicName = created.name)
                        openConversation(updatedChat)
                    }
                },
            )
            OptionsMenuDialog(this, "🧵 Forum Topics (${chat.title})", options).show()
        }
    }

    private fun showGroupMembersMenu(chat: ChatModel) {
        commitActiveInputEngines()
        telegramManager.loadGroupMembers(chat.id) { members, error ->
            if (error != null) showToast(error, isError = true)
            val options = mutableListOf<MenuOption>()
            options.add(
                MenuOption(1, "➕ Add Member to Group") {
                    showAddMemberToGroupMenu(chat)
                },
            )
            members.forEachIndexed { idx, member ->
                val badge =
                    when {
                        member.isCreator -> "👑 Owner"
                        member.isAdmin -> "🛡️ Admin"
                        else -> "👤 Member"
                    }
                options.add(
                    MenuOption(idx + 2, "${member.name} ($badge • ${member.status})") {
                        showMemberAdminActionsMenu(chat, member)
                    },
                )
            }
            OptionsMenuDialog(this, "👥 Members (${members.size})", options).show()
        }
    }

    private fun showMemberAdminActionsMenu(
        chat: ChatModel,
        member: com.teleflip.app.model.GroupMember,
    ) {
        val options = mutableListOf<MenuOption>()
        options.add(
            MenuOption(1, "💬 Message ${member.name}") {
                telegramManager.resolveAndCreateChat(member.name) { directChat, _ ->
                    if (directChat != null) openConversation(directChat)
                }
            },
        )
        if (!member.isCreator) {
            val adminActionLabel = if (member.isAdmin) "🛡️ Demote from Admin" else "🛡️ Promote to Admin"
            options.add(
                MenuOption(options.size + 1, adminActionLabel) {
                    telegramManager.setChatMemberAdminRole(chat.id, member.userId, !member.isAdmin) { success ->
                        showToast(if (success) "Member role updated" else "Could not change member role", isError = !success)
                    }
                },
            )
            options.add(
                MenuOption(options.size + 1, "🚫 Remove from ${chat.title}") {
                    telegramManager.removeChatMember(chat.id, member.userId) { success ->
                        showToast(if (success) "Removed ${member.name}" else "Could not remove member", isError = !success)
                    }
                },
            )
        }
        OptionsMenuDialog(this, member.name, options).show()
    }

    private fun showAddMemberToGroupMenu(chat: ChatModel) {
        commitActiveInputEngines()
        telegramManager.loadContacts { contacts ->
            val options = mutableListOf<MenuOption>()
            contacts.forEachIndexed { idx, contact ->
                val name = "${contact.firstName} ${contact.lastName}".trim().ifBlank { contact.phoneNumber }
                options.add(
                    MenuOption(idx + 1, "👤 Add $name") {
                        telegramManager.addChatMember(chat.id, contact.userId) { success, err ->
                            if (success) {
                                showToast("Added $name to ${chat.title}")
                            } else {
                                showToast("Could not add member: ${err ?: "Failed"}", isError = true)
                            }
                        }
                    },
                )
            }
            if (options.isEmpty()) {
                showToast("No Telegram contacts found")
            } else {
                OptionsMenuDialog(this, "➕ Add Member to ${chat.title}", options).show()
            }
        }
    }

    private fun showSearchInChatDialog(chat: ChatModel) {
        commitActiveInputEngines()
        val container =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(40, 20, 40, 20)
            }
        val etSearch =
            EditText(this).apply {
                hint = "Search text in this chat..."
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_hint))
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_edittext_selector)
                setPadding(24, 20, 24, 20)
                isSingleLine = true
            }
        container.addView(etSearch)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("🔍 Search in ${chat.title}")
            .setView(container)
            .setPositiveButton("Search") { dialog, _ ->
                dialog.dismiss()
                val query = etSearch.text.toString().trim()
                if (query.isNotEmpty()) {
                    telegramManager.searchChatMessages(chat.id, query) { results ->
                        if (results.isEmpty()) {
                            showToast("No messages found matching \"$query\"")
                        } else {
                            val options =
                                results.mapIndexed { idx, res ->
                                    val snippet = if (res.text.length > 50) "${res.text.take(50)}..." else res.text
                                    MenuOption(idx + 1, "[${res.time}] ${res.senderName}: $snippet") {
                                        val pos = messageAdapter?.getPositionForMessageId(res.messageId) ?: -1
                                        if (pos != -1) {
                                            rvMessages?.scrollToPosition(pos)
                                            rvMessages?.post {
                                                rvMessages?.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
                                            }
                                        } else {
                                            showToast("Jumped to message #${res.messageId}")
                                        }
                                    }
                                }
                            OptionsMenuDialog(this@MainActivity, "Results for \"$query\" (${results.size})", options).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .show()

        etSearch.post { etSearch.requestFocus() }
    }

    private fun showClearChatHistoryDialog(chat: ChatModel) {
        commitActiveInputEngines()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Clear Chat History?")
            .setMessage("Are you sure you want to delete all messages in \"${chat.title}\"? This action cannot be undone.")
            .setPositiveButton("Clear History") { dialog, _ ->
                dialog.dismiss()
                telegramManager.deleteChatHistory(chat.id, revoke = true) {
                    showToast("Chat history cleared")
                }
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun showImportCloudHistoryDialog(chat: ChatModel) {
        commitActiveInputEngines()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("📥 Import History from Cloud")
            .setMessage("Fetch all previous messages and media for \"${chat.title}\" directly from Telegram cloud servers?")
            .setPositiveButton("Import All") { dialog, _ ->
                dialog.dismiss()
                val progressDialog = ChatExportProgressDialog(this) {}
                progressDialog.show()
                lifecycleScope.launch {
                    try {
                        val result =
                            telegramManager.importChatHistoryFromCloud(
                                chatId = chat.id,
                                autoDownloadMedia = true,
                                onProgress = { loadedMsgs, queuedMedia ->
                                    progressDialog.updateProgress(
                                        ChatExportProgress(
                                            stage = ExportStage.FETCHING_CLOUD_HISTORY,
                                            messagesProcessed = loadedMsgs,
                                            totalMessages = loadedMsgs,
                                            mediaDownloaded = queuedMedia,
                                            totalMedia = queuedMedia,
                                            statusText = "Imported $loadedMsgs messages ($queuedMedia media queued)...",
                                        ),
                                    )
                                },
                            )
                        progressDialog.dismiss()
                        showToast("✅ Imported ${result.first} messages from cloud (${result.second} media syncing)")
                        val updated = telegramManager.messages.value[chat.id] ?: emptyList()
                        messageAdapter?.submitList(updated)
                        if (updated.isNotEmpty()) {
                            rvMessages?.scrollToPosition(updated.size - 1)
                        }
                    } catch (e: Exception) {
                        progressDialog.dismiss()
                        showToast("Failed to import history: ${e.message}")
                    }
                }
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun showExportChatHistoryDialog(chat: ChatModel) {
        commitActiveInputEngines()
        ChatExportDialog(this, chat.id, chat.title) { options ->
            var exportJob: Job? = null
            val progressDialog =
                ChatExportProgressDialog(this) {
                    exportJob?.cancel()
                }
            progressDialog.show()

            exportJob =
                lifecycleScope.launch {
                    val result =
                        telegramManager.exportChatHistory(
                            chatId = chat.id,
                            chatTitle = chat.title,
                            options = options,
                            onProgress = { progress ->
                                progressDialog.updateProgress(progress)
                            },
                        )
                    if (result.isSuccess) {
                        val exportDir = result.getOrNull()
                        progressDialog.dismiss()
                        val targetName = if (options.targetStorageType == StorageLocationType.SD_CARD) "SD Card" else "Internal Storage"
                        androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                            .setTitle("✅ Chat Downloaded to $targetName")
                            .setMessage("Chat \"${chat.title}\" was successfully exported to:\n\n${exportDir?.absolutePath}\n\nContains messages.html, messages.txt, messages.json, and downloaded media.")
                            .setPositiveButton("OK") { d, _ -> d.dismiss() }
                            .show()
                    } else {
                        progressDialog.dismiss()
                        val err = result.exceptionOrNull()
                        if (err !is kotlinx.coroutines.CancellationException) {
                            showToast("Download failed: ${err?.message}")
                        }
                    }
                }
        }.show()
    }

    private fun showContactsMenu() {
        commitActiveInputEngines()
        telegramManager.loadContacts { contacts ->
            val options = mutableListOf<MenuOption>()
            options.add(
                MenuOption(1, "➕ Add New Contact") {
                    showAddContactDialog()
                },
            )
            contacts.forEachIndexed { idx, contact ->
                val name = "${contact.firstName} ${contact.lastName}".trim().ifBlank { contact.phoneNumber }
                val statusIcon = if (contact.isOnline) "🟢" else "👤"
                val phoneSnippet = if (contact.phoneNumber.isNotBlank()) " (${contact.phoneNumber})" else ""
                options.add(
                    MenuOption(idx + 2, "$statusIcon $name$phoneSnippet") {
                        val query = contact.phoneNumber.ifBlank { contact.username.ifBlank { contact.userId.toString() } }
                        telegramManager.resolveAndCreateChat(query) { directChat, _ ->
                            if (directChat != null) openConversation(directChat)
                        }
                    },
                )
            }
            OptionsMenuDialog(this, "👥 Contacts (${contacts.size})", options).show()
        }
    }

    private fun showAddContactDialog() {
        commitActiveInputEngines()
        val container =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(40, 20, 40, 20)
            }
        val etFirstName =
            EditText(this).apply {
                hint = "First Name"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_hint))
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_edittext_selector)
                setPadding(24, 20, 24, 20)
                isSingleLine = true
            }
        val etLastName =
            EditText(this).apply {
                hint = "Last Name (Optional)"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_hint))
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_edittext_selector)
                setPadding(24, 20, 24, 20)
                isSingleLine = true
            }
        val etPhone =
            EditText(this).apply {
                hint = "Phone Number (+15551234567)"
                inputType = InputType.TYPE_CLASS_PHONE
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_hint))
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_edittext_selector)
                setPadding(24, 20, 24, 20)
                isSingleLine = true
            }
        container.addView(etFirstName)
        container.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 16) })
        container.addView(etLastName)
        container.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 16) })
        container.addView(etPhone)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("➕ Add New Contact")
            .setView(container)
            .setPositiveButton("Save Contact") { dialog, _ ->
                dialog.dismiss()
                val first = etFirstName.text.toString().trim()
                val last = etLastName.text.toString().trim()
                val phone = etPhone.text.toString().trim()
                if (first.isNotBlank() && phone.isNotBlank()) {
                    telegramManager.addContact(phone, first, last) { success, err ->
                        if (success) {
                            showToast("Contact added: $first")
                            telegramManager.resolveAndCreateChat(phone) { directChat, _ ->
                                if (directChat != null) openConversation(directChat)
                            }
                        } else {
                            showToast("Error adding contact: ${err ?: "Unknown"}", isError = true)
                        }
                    }
                } else {
                    showToast("First name and phone number required", isError = true)
                }
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .show()

        etFirstName.post { etFirstName.requestFocus() }
    }

    private fun showEditProfileMenu() {
        commitActiveInputEngines()
        val current = telegramManager.currentUser.value
        val container =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(40, 20, 40, 20)
            }
        val etFirst =
            EditText(this).apply {
                hint = "First Name"
                setText(current?.firstName.orEmpty())
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_hint))
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_edittext_selector)
                setPadding(24, 20, 24, 20)
                isSingleLine = true
            }
        val etLast =
            EditText(this).apply {
                hint = "Last Name"
                setText(current?.lastName.orEmpty())
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_hint))
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_edittext_selector)
                setPadding(24, 20, 24, 20)
                isSingleLine = true
            }
        val etUsername =
            EditText(this).apply {
                hint = "Username (@username)"
                setText(current?.username.orEmpty())
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_hint))
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_edittext_selector)
                setPadding(24, 20, 24, 20)
                isSingleLine = true
            }
        val etBio =
            EditText(this).apply {
                hint = "Bio (About you)"
                setText(current?.bio.orEmpty())
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_hint))
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_edittext_selector)
                setPadding(24, 20, 24, 20)
                isSingleLine = true
            }
        container.addView(etFirst)
        container.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 12) })
        container.addView(etLast)
        container.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 12) })
        container.addView(etUsername)
        container.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 12) })
        container.addView(etBio)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("✏️ Edit Profile")
            .setView(container)
            .setPositiveButton("Save Profile") { dialog, _ ->
                dialog.dismiss()
                val f = etFirst.text.toString().trim()
                val l = etLast.text.toString().trim()
                val u = etUsername.text.toString().trim()
                val b = etBio.text.toString().trim()
                telegramManager.updateProfile(f, l, b, u) { success, err ->
                    if (success) {
                        showToast("Profile updated!")
                    } else {
                        showToast("Profile update error: ${err ?: "Failed"}", isError = true)
                    }
                }
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .show()

        etFirst.post { etFirst.requestFocus() }
    }

    private fun showActiveSessionsMenu() {
        commitActiveInputEngines()
        telegramManager.loadActiveSessions { sessions ->
            val options = mutableListOf<MenuOption>()
            options.add(
                MenuOption(1, "🚫 Terminate All Other Sessions") {
                    telegramManager.terminateAllOtherSessions { ok, err ->
                        if (ok) {
                            showToast("All other sessions terminated")
                        } else {
                            showToast("Error: ${err ?: "Failed"}", isError = true)
                        }
                    }
                },
            )
            sessions.forEachIndexed { idx, session ->
                val currentBadge = if (session.isCurrent) " [This Device]" else ""
                val info = "${session.deviceModel} • ${session.platform} • ${session.ip}$currentBadge"
                options.add(
                    MenuOption(idx + 2, "📱 $info") {
                        showToast("Session: ${session.appName} ${session.appVersion} (${session.activeDate})")
                    },
                )
            }
            OptionsMenuDialog(this, "📱 Active Sessions (${sessions.size})", options).show()
        }
    }

    private fun showStorageLocationDialog(onChanged: () -> Unit) {
        val storageOptions = com.teleflip.app.storage.StorageManager.getStorageOptions(this)
        val currentType = com.teleflip.app.storage.StorageManager.getSelectedStorageType(this)
        val menuOptions =
            storageOptions.mapIndexed { idx, opt ->
                val check = if (opt.type == currentType) "✓ " else ""
                val status = if (opt.isAvailable) opt.description else "Not Mounted"
                MenuOption(idx + 1, "$check💾 ${opt.title}\n   $status") {
                    if (!opt.isAvailable) {
                        showToast("MicroSD Card is not detected. Please insert an SD card.", true)
                    } else {
                        telegramManager.switchStorageLocation(opt.type) { ok, msg ->
                            showToast(msg, !ok)
                            onChanged()
                        }
                    }
                }
            }
        OptionsMenuDialog(this, "💾 Storage Location", menuOptions).show()
    }

    private fun showAutoDownloadDialog(onChanged: () -> Unit) {
        val modes = com.teleflip.app.storage.AutoDownloadMode.values()
        val currentMode = com.teleflip.app.storage.StorageManager.getAutoDownloadMode(this)
        val menuOptions =
            modes.mapIndexed { idx, mode ->
                val check = if (mode == currentMode) "✓ " else ""
                MenuOption(idx + 1, "$check📥 ${mode.label}") {
                    com.teleflip.app.storage.StorageManager.setAutoDownloadMode(this, mode)
                    showToast("Auto-download set to ${mode.label}")
                    onChanged()
                }
            }
        OptionsMenuDialog(this, "📥 Auto-Download Media", menuOptions).show()
    }

    private fun showKeepMediaDialog(onChanged: () -> Unit) {
        val durations = com.teleflip.app.storage.KeepMediaDuration.values()
        val currentDur = com.teleflip.app.storage.StorageManager.getKeepMediaDuration(this)
        val menuOptions =
            durations.mapIndexed { idx, dur ->
                val check = if (dur == currentDur) "✓ " else ""
                MenuOption(idx + 1, "$check⏳ ${dur.label}") {
                    com.teleflip.app.storage.StorageManager.setKeepMediaDuration(this, dur)
                    telegramManager.clearMediaCache(dur.days) { _, freed ->
                        val mb = freed / BYTES_PER_MB
                        showToast("Keep Media: ${dur.label} ($mb MB cleaned)")
                        onChanged()
                    }
                }
            }
        OptionsMenuDialog(this, "⏳ Keep Media Duration", menuOptions).show()
    }

    // =========================================================================
    // Passcode Lock Implementation
    // =========================================================================
    private fun showPasscodeUnlockScreen() {
        navigateTo(Screen.PASSCODE_LOCK) {
            renderPasscodeUnlockScreen()
        }
    }

    private fun renderPasscodeUnlockScreen() {
        commitActiveInputEngines()
        currentScreen = Screen.PASSCODE_LOCK
        mainContainer.removeAllViews()
        val view = LayoutInflater.from(this).inflate(R.layout.view_passcode_lock, mainContainer, true)

        passcodeEntry = ""
        tvPasscodeDots = view.findViewById(R.id.tv_passcode_dots)
        tvPasscodeError = view.findViewById(R.id.tv_passcode_error)
        updatePasscodeDotsDisplay()

        val btnForgot = view.findViewById<Button>(R.id.btn_passcode_forgot)
        btnForgot.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Forgot Passcode?")
                .setMessage("To reset your passcode, you will need to log out and sign in again. All local passcode settings will be cleared.")
                .setPositiveButton("Log Out & Reset") { _, _ ->
                    PasscodeManager.disablePasscode(this)
                    telegramManager.logOut()
                    showLoginScreen(AuthState.WaitPhoneNumber())
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        softkeyManager.update(
            left = "",
            center = "Refresh",
            right = "Clear",
            onLeft = {},
            onCenter = {
                if (passcodeEntry.length == PASSCODE_PIN_LENGTH) {
                    attemptPasscodeUnlock()
                }
            },
            onRight = {
                if (passcodeEntry.isNotEmpty()) {
                    passcodeEntry = passcodeEntry.dropLast(1)
                    updatePasscodeDotsDisplay()
                }
            },
        )

        if (PasscodeManager.canAuthenticate(this)) {
            PasscodeManager.promptBiometricUnlock(
                activity = this,
                onSuccess = {
                    tvPasscodeError?.text = ""
                    if (telegramManager.authState.value is AuthState.Ready) {
                        showChatListScreen()
                    } else {
                        showLoginScreen(telegramManager.authState.value)
                    }
                },
                onError = { err ->
                    if (err.isNotBlank()) {
                        tvPasscodeError?.text = err
                    }
                },
            )
        }
    }

    private fun updatePasscodeDotsDisplay() {
        val dots = StringBuilder()
        for (i in 0 until PASSCODE_PIN_LENGTH) {
            if (i > 0) dots.append("   ")
            if (i < passcodeEntry.length) {
                dots.append("●")
            } else {
                dots.append("○")
            }
        }
        tvPasscodeDots?.text = dots.toString()
        val enteredCount = passcodeEntry.length
        tvPasscodeDots?.contentDescription = "$enteredCount of $PASSCODE_PIN_LENGTH digits entered"
        if (passcodeEntry.isEmpty()) {
            tvPasscodeError?.text = ""
        }
    }

    @JvmName("attemptPasscodeUnlock")
    internal fun attemptPasscodeUnlock() {
        val unlocked = PasscodeManager.unlock(this, passcodeEntry)
        passcodeEntry = ""
        if (unlocked) {
            tvPasscodeError?.text = ""
            if (pendingTargetChatId != 0L) {
                val targetId = pendingTargetChatId
                pendingTargetChatId = 0L
                pendingNavigationAction = null
                val targetChat =
                    telegramManager.chats.value.find { it.id == targetId }
                        ?: ChatModel(
                            id = targetId,
                            title = "Chat #$targetId",
                            lastMessage = "",
                            time = "",
                            unreadCount = 0,
                            chatType = ChatType.DIRECT,
                            avatarLetters = "TG",
                        )
                openConversation(targetChat)
            } else {
                val pending = pendingNavigationAction
                pendingNavigationAction = null
                if (pending != null) {
                    pending.invoke()
                } else if (telegramManager.authState.value is AuthState.Ready) {
                    showChatListScreen()
                } else {
                    showLoginScreen(telegramManager.authState.value)
                }
            }
        } else {
            updatePasscodeDotsDisplay()
            val errorMsg = "Incorrect PIN. Try again."
            tvPasscodeError?.text = errorMsg
            tvPasscodeError?.announceForAccessibility(errorMsg)
        }
    }

    private fun showPasscodeSettingsDialog(onChanged: () -> Unit) {
        commitActiveInputEngines()
        val isEnabled = PasscodeManager.isEnabled(this)
        val options = mutableListOf<MenuOption>()
        if (!isEnabled) {
            options.add(
                MenuOption(1, "🔒 Enable 4-Digit Passcode") {
                    promptNewPasscode { pin ->
                        PasscodeManager.setPasscode(this, pin)
                        showToast("Passcode lock enabled")
                        onChanged()
                    }
                },
            )
        } else {
            options.add(
                MenuOption(1, "🔑 Change Passcode") {
                    promptVerifyCurrentPasscode {
                        promptNewPasscode { pin ->
                            PasscodeManager.setPasscode(this, pin)
                            showToast("Passcode updated successfully")
                            onChanged()
                        }
                    }
                },
            )
            val timeoutLabel = PasscodeManager.getAutoLockLabel(this)
            options.add(
                MenuOption(2, "⏱️ Auto-Lock: $timeoutLabel") {
                    showAutoLockTimeoutDialog {
                        onChanged()
                    }
                },
            )
            options.add(
                MenuOption(3, "🔓 Turn Off Passcode Lock") {
                    promptVerifyCurrentPasscode {
                        PasscodeManager.disablePasscode(this)
                        showToast("Passcode lock disabled")
                        onChanged()
                    }
                },
            )
        }
        OptionsMenuDialog(this, "🔒 Passcode Lock Settings", options).show()
    }

    private fun showAutoLockTimeoutDialog(onChanged: () -> Unit) {
        val options =
            listOf(
                MenuOption(1, "Immediately") {
                    PasscodeManager.setAutoLockTimeoutSeconds(this, PasscodeManager.TIMEOUT_IMMEDIATE)
                    showToast("Auto-lock set to Immediately")
                    onChanged()
                },
                MenuOption(2, "After 1 Minute") {
                    PasscodeManager.setAutoLockTimeoutSeconds(this, PasscodeManager.TIMEOUT_1_MIN)
                    showToast("Auto-lock set to 1 minute")
                    onChanged()
                },
                MenuOption(3, "After 5 Minutes") {
                    PasscodeManager.setAutoLockTimeoutSeconds(this, PasscodeManager.TIMEOUT_5_MIN)
                    showToast("Auto-lock set to 5 minutes")
                    onChanged()
                },
            )
        OptionsMenuDialog(this, "⏱️ Auto-Lock Timeout", options).show()
    }

    private fun promptVerifyCurrentPasscode(onSuccess: () -> Unit) {
        val input =
            EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                filters = arrayOf(android.text.InputFilter.LengthFilter(PASSCODE_PIN_LENGTH))
                hint = "Current 4-digit PIN"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_hint))
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_edittext_selector)
                setPadding(24, 20, 24, 20)
            }
        AlertDialog.Builder(this)
            .setTitle("Enter Current Passcode")
            .setView(input)
            .setPositiveButton("Verify") { _, _ ->
                val pin = input.text.toString().trim()
                if (PasscodeManager.verifyPasscode(this, pin)) {
                    onSuccess()
                } else {
                    showToast("Incorrect passcode", isError = true)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptNewPasscode(onSuccess: (String) -> Unit) {
        val input =
            EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                filters = arrayOf(android.text.InputFilter.LengthFilter(PASSCODE_PIN_LENGTH))
                hint = "New 4-digit PIN"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_hint))
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_edittext_selector)
                setPadding(24, 20, 24, 20)
            }
        AlertDialog.Builder(this)
            .setTitle("Set 4-Digit Passcode")
            .setView(input)
            .setPositiveButton("Next") { _, _ ->
                val pin = input.text.toString().trim()
                if (pin.length == PASSCODE_PIN_LENGTH) {
                    promptConfirmPasscode(pin, onSuccess)
                } else {
                    showToast("Passcode must be exactly 4 digits", isError = true)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptConfirmPasscode(
        expectedPin: String,
        onSuccess: (String) -> Unit,
    ) {
        val input =
            EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                filters = arrayOf(android.text.InputFilter.LengthFilter(PASSCODE_PIN_LENGTH))
                hint = "Re-enter 4-digit PIN"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_hint))
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_edittext_selector)
                setPadding(24, 20, 24, 20)
            }
        AlertDialog.Builder(this)
            .setTitle("Confirm Passcode")
            .setView(input)
            .setPositiveButton("Confirm") { _, _ ->
                val confirmPin = input.text.toString().trim()
                if (confirmPin == expectedPin) {
                    onSuccess(expectedPin)
                } else {
                    showToast("Passcodes do not match", isError = true)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // =========================================================================
    // Chat Notification Mute Implementation
    // =========================================================================
    private fun showChatMuteDialog(
        chatId: Long,
        isCurrentlyMuted: Boolean,
        chatTitle: String,
        onMuteStateChanged: ((Boolean) -> Unit)? = null,
    ) {
        commitActiveInputEngines()
        val options = mutableListOf<MenuOption>()
        if (isCurrentlyMuted) {
            options.add(
                MenuOption(1, "🔔 Unmute Notifications") {
                    telegramManager.setChatNotificationMute(chatId, 0) { isMuted ->
                        showToast("Unmuted $chatTitle")
                        onMuteStateChanged?.invoke(isMuted)
                    }
                },
            )
        } else {
            options.add(
                MenuOption(1, "🔇 Mute for 1 Hour") {
                    telegramManager.setChatNotificationMute(chatId, MUTE_1_HOUR_SEC) { isMuted ->
                        showToast("Muted $chatTitle for 1 hour")
                        onMuteStateChanged?.invoke(isMuted)
                    }
                },
            )
            options.add(
                MenuOption(2, "🔇 Mute for 8 Hours") {
                    telegramManager.setChatNotificationMute(chatId, MUTE_8_HOURS_SEC) { isMuted ->
                        showToast("Muted $chatTitle for 8 hours")
                        onMuteStateChanged?.invoke(isMuted)
                    }
                },
            )
            options.add(
                MenuOption(3, "🔇 Mute for 2 Days") {
                    telegramManager.setChatNotificationMute(chatId, MUTE_2_DAYS_SEC) { isMuted ->
                        showToast("Muted $chatTitle for 2 days")
                        onMuteStateChanged?.invoke(isMuted)
                    }
                },
            )
            options.add(
                MenuOption(4, "🔇 Mute Forever") {
                    telegramManager.setChatNotificationMute(chatId, MUTE_FOREVER_SEC) { isMuted ->
                        showToast("Muted $chatTitle forever")
                        onMuteStateChanged?.invoke(isMuted)
                    }
                },
            )
        }
        OptionsMenuDialog(this, "Notifications: $chatTitle", options).show()
    }

    // =========================================================================
    // Blocked Users Management Implementation
    // =========================================================================
    private fun showBlockedUsersDialog() {
        commitActiveInputEngines()
        telegramManager.loadBlockedUsers { blockedList ->
            val options = mutableListOf<MenuOption>()
            options.add(
                MenuOption(1, "🚫 Block User from Contacts...") {
                    showBlockUserSelectionDialog()
                },
            )
            blockedList.forEachIndexed { index, contact ->
                val name = "${contact.firstName} ${contact.lastName}".trim().ifBlank { "User ${contact.userId}" }
                val handle = if (contact.username.isNotBlank()) " (@${contact.username})" else ""
                options.add(
                    MenuOption(index + 2, "❌ Unblock $name$handle") {
                        confirmUnblockUser(contact.userId, name)
                    },
                )
            }
            val title = "🚫 Blocked Users (${blockedList.size})"
            OptionsMenuDialog(this, title, options).show()
        }
    }

    private fun confirmUnblockUser(
        userId: Long,
        name: String,
    ) {
        AlertDialog.Builder(this)
            .setTitle("Unblock User")
            .setMessage("Are you sure you want to unblock $name?")
            .setPositiveButton("Unblock") { _, _ ->
                telegramManager.unblockUser(userId) { success, err ->
                    if (success) {
                        showToast("Unblocked $name")
                        showBlockedUsersDialog()
                    } else {
                        showToast(err ?: "Failed to unblock", isError = true)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showBlockUserSelectionDialog() {
        commitActiveInputEngines()
        telegramManager.loadContacts { contacts ->
            val options = mutableListOf<MenuOption>()
            contacts.forEachIndexed { idx, contact ->
                val name = "${contact.firstName} ${contact.lastName}".trim().ifBlank { contact.phoneNumber }
                options.add(
                    MenuOption(idx + 1, "🚫 Block $name") {
                        confirmBlockUser(contact.userId, name)
                    },
                )
            }
            if (options.isEmpty()) {
                showToast("No contacts found to block")
            } else {
                OptionsMenuDialog(this, "Select Contact to Block", options).show()
            }
        }
    }

    private fun confirmBlockUser(
        userId: Long,
        name: String,
    ) {
        AlertDialog.Builder(this)
            .setTitle("Block User")
            .setMessage("Are you sure you want to block $name? They will not be able to send you messages or call you.")
            .setPositiveButton("Block") { _, _ ->
                telegramManager.blockUser(userId) { success, err ->
                    if (success) {
                        showToast("Blocked $name")
                        if (currentScreen == Screen.SETTINGS) {
                            showBlockedUsersDialog()
                        }
                    } else {
                        showToast(err ?: "Failed to block", isError = true)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSelfDestructTimerMenu(chat: ChatModel) {
        val presets =
            listOf(
                0 to "Off (Keep Messages)",
                5 to "5 Seconds",
                15 to "15 Seconds",
                30 to "30 Seconds",
                60 to "1 Minute",
                3600 to "1 Hour",
            )
        val options =
            presets.mapIndexed { idx, (sec, label) ->
                val prefix = if (chat.selfDestructTimerSec == sec) "✓ " else ""
                MenuOption(idx + 1, "$prefix⏱️ $label") {
                    telegramManager.setSelfDestructTimer(chat.id, sec) { appliedSec ->
                        val updated = chat.copy(selfDestructTimerSec = appliedSec)
                        activeChat = updated
                        showToast(if (appliedSec == 0) "Self-destruct timer turned OFF" else "Self-destruct timer set to $label")
                        if (currentScreen == Screen.CONVERSATION) {
                            openConversation(updated)
                        } else if (currentScreen == Screen.PROFILE) {
                            showProfileScreen(updated)
                        }
                    }
                }
            }
        OptionsMenuDialog(this, "⏱️ Self-Destruct Timer", options).show()
    }

    private fun showSendMediaPicker(
        chat: ChatModel,
        isPhoto: Boolean,
        isSticker: Boolean = false,
    ) {
        val searchDirs =
            if (isPhoto) {
                listOf(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                )
            } else {
                listOf(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                )
            }

        val allowedExts =
            if (isPhoto) {
                setOf("jpg", "jpeg", "png", "webp", "gif")
            } else {
                setOf("pdf", "txt", "zip", "apk", "doc", "docx", "xls", "xlsx", "csv", "mp3", "mp4", "jpg", "png")
            }

        val recentFiles =
            searchDirs
                .filterNotNull()
                .filter { it.exists() && it.isDirectory }
                .flatMap { dir ->
                    dir.walkTopDown().maxDepth(2).filter { file ->
                        file.isFile && (isPhoto.not() || file.extension.lowercase() in allowedExts)
                    }.toList()
                }
                .sortedByDescending { it.lastModified() }
                .take(7)

        val options = mutableListOf<MenuOption>()
        options.add(
            MenuOption(1, if (isPhoto) "📂 Browse System Gallery..." else "📂 Browse System Files...") {
                try {
                    val intent =
                        Intent(Intent.ACTION_GET_CONTENT).apply {
                            type = if (isPhoto) "image/*" else "*/*"
                            addCategory(Intent.CATEGORY_OPENABLE)
                        }
                    startActivityForResult(
                        intent,
                        if (isSticker) {
                            REQ_PICK_STICKER
                        } else if (isPhoto) {
                            REQ_PICK_PHOTO
                        } else {
                            REQ_PICK_DOCUMENT
                        },
                    )
                } catch (e: Exception) {
                    showToast("System file picker unavailable on this device", isError = true)
                }
            },
        )

        val caption = etComposeInput?.text?.toString()?.trim().orEmpty()
        recentFiles.forEach { file ->
            val label = if (isPhoto) "📷 ${file.name}" else "📄 ${file.name}"
            options.add(
                MenuOption(options.size + 1, label) {
                    if (isSticker) {
                        telegramManager.sendLocalSticker(chat.id, file.absolutePath)
                    } else if (isPhoto) {
                        telegramManager.sendPhoto(chat.id, file.absolutePath, caption)
                        showToast("Sending photo: ${file.name}")
                    } else {
                        telegramManager.sendDocument(chat.id, file.absolutePath, caption)
                        showToast("Sending document: ${file.name}")
                    }
                    if (caption.isNotEmpty()) {
                        etComposeInput?.setText("")
                    }
                },
            )
        }

        val title = if (isPhoto) "Send Photo / Image" else "Send File / Document"
        OptionsMenuDialog(this, title, options).show()
    }

    private fun showStickerPickerMenu(chat: ChatModel) {
        val rootOptions = mutableListOf<MenuOption>()

        rootOptions.add(
            MenuOption(rootOptions.size + 1, "📦 My Telegram Sticker Packs") {
                telegramManager.loadInstalledStickers { sets ->
                    if (sets.isEmpty()) {
                        showToast("No installed sticker sets found")
                    } else {
                        showInstalledStickerPacksMenu(chat, sets)
                    }
                }
                showToast("Loading sticker packs...")
            },
        )

        rootOptions.add(
            MenuOption(rootOptions.size + 1, "⭐ Popular Emoji Stickers") {
                showPopularStickersMenu(chat)
            },
        )

        rootOptions.add(
            MenuOption(rootOptions.size + 1, "📁 Pick Sticker from Device (.webp/.png)") {
                showSendMediaPicker(chat, isPhoto = true, isSticker = true)
            },
        )

        OptionsMenuDialog(this, "Send Sticker", rootOptions).show()
    }

    private fun showInstalledStickerPacksMenu(
        chat: ChatModel,
        sets: List<StickerSetModel>,
    ) {
        val packOptions =
            sets.mapIndexed { idx, set ->
                MenuOption(idx + 1, "📦 ${set.title} (${set.stickers.size})") {
                    showStickerPackItemsMenu(chat, set)
                }
            }
        OptionsMenuDialog(this, "Sticker Packs", packOptions).show()
    }

    private fun showStickerPackItemsMenu(
        chat: ChatModel,
        set: StickerSetModel,
    ) {
        val stickerOptions =
            set.stickers.take(30).mapIndexed { idx, st ->
                val label = if (st.emoji.isNotBlank()) "${st.emoji} Sticker ${idx + 1}" else "Sticker ${idx + 1}"
                MenuOption(idx + 1, label) {
                    telegramManager.sendSticker(chat.id, st.fileId, st.emoji)
                    showToast("Sending sticker…")
                }
            }
        OptionsMenuDialog(this, set.title.take(20), stickerOptions).show()
    }

    private fun showPopularStickersMenu(chat: ChatModel) {
        val popular =
            listOf(
                "👍" to "👍 Thumbs Up",
                "❤️" to "❤️ Red Heart",
                "😂" to "😂 Laughing",
                "🔥" to "🔥 Fire",
                "🎉" to "🎉 Party / Celebrate",
                "🙏" to "🙏 Thank You",
                "😎" to "😎 Cool",
                "🚀" to "🚀 Rocket",
                "👏" to "👏 Applause",
                "💯" to "💯 100",
                "🤔" to "🤔 Thinking",
                "😢" to "😢 Crying",
            )
        val options =
            popular.mapIndexed { idx, (emoji, label) ->
                MenuOption(idx + 1, label) {
                    telegramManager.sendStickerEmoji(chat.id, emoji)
                    showToast("Finding sticker $emoji…")
                }
            }
        OptionsMenuDialog(this, "Popular Stickers", options).show()
    }

    private fun showGifPickerMenu(chat: ChatModel) {
        val rootOptions = mutableListOf<MenuOption>()

        rootOptions.add(
            MenuOption(rootOptions.size + 1, "💾 Saved Telegram GIFs") {
                telegramManager.loadSavedGifs { gifs ->
                    if (gifs.isEmpty()) {
                        showToast("No saved GIFs found")
                    } else {
                        showSavedGifsMenu(chat, gifs)
                    }
                }
                showToast("Loading saved GIFs...")
            },
        )

        rootOptions.add(
            MenuOption(rootOptions.size + 1, "⚡ Popular Reaction Loops") {
                showReactionGifsMenu(chat)
            },
        )

        rootOptions.add(
            MenuOption(rootOptions.size + 1, "📁 Pick GIF from Device (.gif/.mp4)") {
                showSendMediaPicker(chat, isPhoto = false)
            },
        )

        OptionsMenuDialog(this, "Send GIF / Animation", rootOptions).show()
    }

    private fun showSavedGifsMenu(
        chat: ChatModel,
        gifs: List<SavedGifItem>,
    ) {
        val gifOptions =
            gifs.take(25).mapIndexed { idx, gif ->
                val name = gif.caption.ifBlank { "GIF #${idx + 1}" }
                MenuOption(idx + 1, "🎞️ $name (${gif.durationSeconds}s)") {
                    telegramManager.sendSavedAnimation(chat.id, gif.fileId, gif.durationSeconds, gif.caption)
                    showToast("Sending GIF…")
                }
            }
        OptionsMenuDialog(this, "Saved GIFs", gifOptions).show()
    }

    private fun showReactionGifsMenu(chat: ChatModel) {
        val reactions =
            listOf(
                "🎉 Celebration / Party",
                "👋 Hello / Wave",
                "👏 Applause / Bravo",
                "🤣 Rolling with Laughter",
                "👍 Thumbs Up",
                "🔥 Fire / Awesome",
                "💃 Dance / Groove",
                "🤝 Deal / Handshake",
            )
        val options =
            reactions.mapIndexed { idx, title ->
                MenuOption(idx + 1, title) {
                    telegramManager.sendMessage(chat.id, "🎞️ [$title]")
                    showToast("Updating reaction…")
                }
            }
        OptionsMenuDialog(this, "Reaction GIFs", options).show()
    }

    private fun showBotCommandsMenu(chat: ChatModel) {
        featureMenus.commands(chat)
    }

    private fun showReactionPickerDialog(msg: MessageModel) {
        val reactionChoices =
            listOf(
                "👍" to "👍 Thumbs Up",
                "❤️" to "❤️ Heart",
                "😂" to "😂 Laugh",
                "🔥" to "🔥 Fire",
                "🎉" to "🎉 Celebrate",
                "🙏" to "🙏 Thanks",
                "😢" to "😢 Sad",
                "👀" to "👀 Eyes",
            )
        val options = mutableListOf<MenuOption>()
        reactionChoices.forEachIndexed { idx, (emoji, label) ->
            val hasReaction = msg.reactions.contains(emoji)
            val prefix = if (hasReaction) "✓ " else ""
            options.add(
                MenuOption(idx + 1, "$prefix$label") {
                    telegramManager.toggleMessageReaction(msg.chatId, msg.id, emoji)
                    showToast(if (hasReaction) "Removed $emoji reaction" else "Reacted with $emoji")
                },
            )
        }
        options.add(
            MenuOption(options.size + 1, "✨ Custom Emoji...") {
                showCustomEmojiPickerDialog(msg)
            },
        )
        OptionsMenuDialog(this, "React to Message", options).show()
    }

    private fun showCustomEmojiPickerDialog(msg: MessageModel) {
        telegramManager.fetchCustomEmojiStickers { customEmojis ->
            val options =
                customEmojis.mapIndexed { idx, item ->
                    val key = "custom_emoji_${item.fileId}"
                    val hasReaction = msg.reactions.contains(key)
                    val prefix = if (hasReaction) "✓ " else ""
                    val label = "$prefix${item.emoji} ${item.setName}"
                    MenuOption(idx + 1, label) {
                        telegramManager.toggleMessageReaction(msg.chatId, msg.id, key)
                        showToast(if (hasReaction) "Removed custom emoji reaction" else "Reacted with ${item.emoji}")
                    }
                }
            if (options.isEmpty()) {
                showToast("No custom emojis available")
            } else {
                OptionsMenuDialog(this, "Select Custom Emoji", options).show()
            }
        }
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
            options.add(
                MenuOption(options.size + 1, "📞 Phone Call ($phoneNumber)") {
                    val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phoneNumber"))
                    try {
                        startActivity(dialIntent)
                    } catch (e: Exception) {
                        showToast("Could not open phone dialer: ${e.message}", true)
                    }
                },
            )
        } else {
            options.add(
                MenuOption(options.size + 1, "📞 Cellular Dialer") {
                    val dialIntent = Intent(Intent.ACTION_DIAL)
                    try {
                        startActivity(dialIntent)
                    } catch (e: Exception) {
                        showToast("Could not open phone dialer: ${e.message}", true)
                    }
                },
            )
        }

        options.add(
            MenuOption(options.size + 1, "📱 Telegram Voice Call") {
                showToast("Connecting Telegram Voice Call to ${chat.title}...")
                initiateAudioCall(userId) { success, msg ->
                    if (success) {
                        showToast("Calling ${chat.title}...")
                    } else {
                        showToast(msg ?: "Telegram call failed", true)
                    }
                }
            },
        )

        OptionsMenuDialog(this, "Call ${chat.title}", options).show()
    }

    private fun showForwardDialog(msg: MessageModel) {
        val allChats = telegramManager.chats.value
        val targetChats = allChats.filter { it.id != msg.chatId }
        if (targetChats.isEmpty()) {
            showToast("No other chats available to forward to")
            return
        }

        val options =
            targetChats.take(20).mapIndexed { idx, chat ->
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
                val targetDirType =
                    when (msg.mediaType) {
                        MediaType.PHOTO, MediaType.STICKER, MediaType.ANIMATION -> Environment.DIRECTORY_PICTURES
                        MediaType.VOICE, MediaType.AUDIO -> Environment.DIRECTORY_MUSIC
                        MediaType.VIDEO -> Environment.DIRECTORY_MOVIES
                        else -> Environment.DIRECTORY_DOWNLOADS
                    }

                val publicDir =
                    if (com.teleflip.app.storage.StorageManager.getSelectedStorageType(this) == com.teleflip.app.storage.StorageLocationType.SD_CARD) {
                        com.teleflip.app.storage.StorageManager.getDownloadDirectory(this)
                    } else {
                        File(Environment.getExternalStoragePublicDirectory(targetDirType), "TeleFlip")
                    }
                if (!publicDir.exists()) {
                    publicDir.mkdirs()
                }

                val extension =
                    sourceFile.extension.ifBlank {
                        when (msg.mediaType) {
                            MediaType.PHOTO -> "jpg"
                            MediaType.STICKER -> "webp"
                            MediaType.ANIMATION -> "gif"
                            MediaType.VOICE, MediaType.AUDIO -> "m4a"
                            MediaType.VIDEO -> "mp4"
                            else -> "bin"
                        }
                    }

                val baseName =
                    if (msg.mediaCaption.isNotBlank() && !msg.mediaCaption.contains("\n")) {
                        msg.mediaCaption.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(30)
                    } else {
                        "TeleFlip_${msg.id}"
                    }
                val destFile = File(publicDir, "$baseName.$extension")

                sourceFile.copyTo(destFile, overwrite = true)

                MediaScannerConnection.scanFile(
                    this,
                    arrayOf(destFile.absolutePath),
                    null,
                ) { scannedPath, _ ->
                    Log.i("TeleFlip", "Media indexed: $scannedPath")
                }

                showToast("Saved to ${publicDir.name}/${destFile.name}")
            } catch (e: Exception) {
                Log.e("TeleFlip", "Failed to save file: ${e.message}", e)
                showToast("Failed to save file: ${e.message}", true)
            }
        } else if (msg.mediaFileId != 0) {
            withDownloadedMedia(msg) { saveMessageToDevice(it) }
        } else if (msg.mediaType == MediaType.NONE && msg.text.isNotBlank()) {
            try {
                val publicDir = com.teleflip.app.storage.StorageManager.getDownloadDirectory(this)
                if (!publicDir.exists()) {
                    publicDir.mkdirs()
                }
                val destFile = File(publicDir, "TeleFlip_Note_${msg.id}.txt")
                destFile.writeText("Chat: ${activeChat?.title ?: ""}\nSender: ${msg.senderName}\nTime: ${msg.time}\n\n${msg.text}\n")

                MediaScannerConnection.scanFile(
                    this,
                    arrayOf(destFile.absolutePath),
                    null,
                    null,
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
            options.add(
                MenuOption(options.size + 1, "Resend Message") {
                    handleResendMessage(msg)
                },
            )
        }

        options.add(
            MenuOption(options.size + 1, "😊 React with Emoji") {
                showReactionPickerDialog(msg)
            },
        )
        options.add(
            MenuOption(options.size + 1, "↩ Reply to Message") {
                startReplyToMessage(msg)
            },
        )
        if (msg.isOutbound && msg.mediaType == MediaType.NONE && msg.text.isNotBlank()) {
            options.add(
                MenuOption(options.size + 1, "✏️ Edit Message") {
                    startEditingMessage(msg)
                },
            )
        }
        options.add(
            MenuOption(options.size + 1, if (msg.isPinned) "📌 Unpin Message" else "📌 Pin Message") {
                telegramManager.togglePinMessage(msg.chatId, msg.id, msg.isPinned) { isNowPinned ->
                    showToast(if (isNowPinned) "Message pinned" else "Message unpinned")
                }
            },
        )
        options.add(
            MenuOption(options.size + 1, "Copy Text") {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Telegram Message", msg.text))
                showToast("Copied to clipboard")
            },
        )
        options.add(
            MenuOption(options.size + 1, "↪ Forward Message") {
                showForwardDialog(msg)
            },
        )

        if (msg.mediaType == MediaType.PHOTO || msg.mediaType == MediaType.VIDEO || msg.mediaType == MediaType.ANIMATION || msg.mediaType == MediaType.STICKER) {
            options.add(
                MenuOption(options.size + 1, "🔍 View Full-Screen / Play Media") {
                    openMediaViewer(msg)
                },
            )
        }
        if (msg.mediaType == MediaType.PAID_MEDIA) {
            if (msg.isPaidMediaLocked) {
                options.add(
                    MenuOption(options.size + 1, "🔓 Unlock Content (⭐ ${msg.starCount} Stars)") {
                        handlePaidMediaAction(msg)
                    },
                )
            } else {
                options.add(
                    MenuOption(options.size + 1, "🔍 View Full-Screen / Play Media") {
                        openMediaViewer(msg)
                    },
                )
            }
        }
        if (msg.mediaType == MediaType.POLL) {
            options.add(
                MenuOption(options.size + 1, "📊 Vote / Manage Poll") {
                    showPollVoteMenu(msg)
                },
            )
        }
        if (msg.mediaType != MediaType.NONE && msg.mediaType != MediaType.CONTACT) {
            options.add(
                MenuOption(options.size + 1, "💾 Save Media to Device") {
                    saveMessageToDevice(msg)
                },
            )
        } else if (msg.text.isNotBlank()) {
            options.add(
                MenuOption(options.size + 1, "💾 Save Text to File") {
                    saveMessageToDevice(msg)
                },
            )
        }

        if (msg.mediaType == MediaType.LOCATION) {
            options.add(
                MenuOption(options.size + 1, "Open in Organic Maps") {
                    LocationHelper.openInOrganicMaps(this, msg.latitude, msg.longitude)
                },
            )
            options.add(
                MenuOption(options.size + 1, "Copy Google Maps Link") {
                    LocationHelper.copyGoogleMapsLink(this, msg.latitude, msg.longitude)
                },
            )
        }

        if (msg.mediaType in setOf(MediaType.VOICE, MediaType.AUDIO)) {
            val isPlaying = msg.mediaFilePath != null && voicePlayer.isPlayingPath(msg.mediaFilePath)
            options.add(
                MenuOption(options.size + 1, if (isPlaying) "Pause Voice Note" else "Play Voice Note") {
                    handlePlayVoiceMessage(msg)
                },
            )
            options.add(
                MenuOption(options.size + 1, "Speed 1.0x") {
                    voicePlayer.setPlaybackSpeed(1.0f)
                    messageAdapter?.setPlaybackSpeed(1.0f)
                    activeChat?.let { updateSoftkeysForFocusedMessage(it, msg) }
                },
            )
            options.add(
                MenuOption(options.size + 1, "Speed 1.5x") {
                    voicePlayer.setPlaybackSpeed(1.5f)
                    messageAdapter?.setPlaybackSpeed(1.5f)
                    activeChat?.let { updateSoftkeysForFocusedMessage(it, msg) }
                },
            )
            options.add(
                MenuOption(options.size + 1, "Speed 2.0x") {
                    voicePlayer.setPlaybackSpeed(2.0f)
                    messageAdapter?.setPlaybackSpeed(2.0f)
                    activeChat?.let { updateSoftkeysForFocusedMessage(it, msg) }
                },
            )
            options.add(
                MenuOption(options.size + 1, "🔤 Transcribe Voice Note") {
                    handleTranscribeVoiceMessage(msg)
                },
            )
        }

        if (msg.formatting.orEmpty().any { it.kind == "url" }) options.add(MenuOption(options.size + 1, "Open a link in this message") { featureMenus.links(msg) })
        if (msg.formatting.orEmpty().any { it.kind == "spoiler" }) options.add(MenuOption(options.size + 1, "Reveal spoilers") { featureMenus.reveal(msg) })
        options.add(MenuOption(options.size + 1, "Save to Saved Messages") { featureMenus.save(msg) })
        if (activeChat?.chatType in setOf(ChatType.GROUP, ChatType.CHANNEL)) options.add(MenuOption(options.size + 1, "Replies / comments") { featureMenus.replies(msg) })
        options.add(MenuOption(options.size + 1, "Copy message link") { featureMenus.link(msg) })
        if (msg.text.isNotBlank() || msg.mediaCaption.isNotBlank()) options.add(MenuOption(options.size + 1, "Translate message") { featureMenus.translate(msg) })
        if (msg.hasBotButtons) options.add(MenuOption(options.size + 1, "Bot buttons") { featureMenus.buttons(msg) })
        if (msg.mediaType == MediaType.CONTACT) options.add(MenuOption(options.size + 1, "Open contact") { featureMenus.contact(msg) })
        if (msg.isOutbound && msg.mediaType in setOf(MediaType.PHOTO, MediaType.VIDEO, MediaType.ANIMATION, MediaType.AUDIO, MediaType.VOICE, MediaType.DOCUMENT)) {
            options.add(
                MenuOption(
                    options.size + 1,
                    "Edit caption",
                ) {
                    featureMenus.caption(msg)
                },
            )
        }
        options.add(MenuOption(options.size + 1, "Delete Message") { featureMenus.delete(msg) })

        OptionsMenuDialog(this, "Message Actions", options).show()
    }

    private fun showQuickRepliesMenu(chat: ChatModel) {
        commitActiveInputEngines()
        val quicks =
            listOf(
                "OK! 👍",
                "On my way! 🚗",
                "Call you in a few minutes.",
                "Sounds good!",
                "Can't talk right now, will reply later.",
                "Sent from my Android Flip Phone 📱",
            )
        val options =
            quicks.mapIndexed { index, text ->
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
        navigateTo(Screen.LOGIN) {
            renderLoginScreen(state)
        }
    }

    private fun renderLoginScreen(state: AuthState) {
        commitActiveInputEngines()
        currentScreen = Screen.LOGIN
        mainContainer.removeAllViews()

        val view = LayoutInflater.from(this).inflate(R.layout.view_login, mainContainer, true)

        val tvStatus = view.findViewById<TextView>(R.id.tv_login_status)
        val tvLabel = view.findViewById<TextView>(R.id.tv_input_label)
        val tvMode = view.findViewById<TextView>(R.id.tv_login_input_mode)
        etLoginInput = view.findViewById(R.id.et_login_input)
        val btnSubmit = view.findViewById<Button>(R.id.btn_login_submit)

        val layoutInputContainer = view.findViewById<View>(R.id.layout_login_input_container)
        val layoutQrContainer = view.findViewById<View>(R.id.layout_qr_container)
        val ivQrCode = view.findViewById<ImageView>(R.id.iv_login_qr)
        val btnPhoneLogin = view.findViewById<Button>(R.id.btn_phone_login)
        val btnQrLogin = view.findViewById<Button>(R.id.btn_qr_login)

        val btnRetry = view.findViewById<Button>(R.id.btn_retry_connection)
        val btnForgotPassword = view.findViewById<Button>(R.id.btn_forgot_password)
        val btnResetAccount = view.findViewById<Button>(R.id.btn_reset_account)
        val btnUseSim = view.findViewById<Button>(R.id.btn_use_sim_number)
        val btnConfig = view.findViewById<Button>(R.id.btn_config_credentials)

        btnQrLogin?.setOnClickListener {
            telegramManager.requestQrCodeLogin()
        }
        btnPhoneLogin?.setOnClickListener {
            telegramManager.showPhoneNumberLogin()
        }

        loginInputEngine =
            KeypadInputEngine(
                editText = etLoginInput!!,
                modeIndicator = tvMode,
                timeoutMs = telegramManager.keypadTimeoutMs,
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
                layoutInputContainer?.visibility = View.VISIBLE
                layoutQrContainer?.visibility = View.GONE
                btnSubmit.visibility = View.VISIBLE
                btnQrLogin?.visibility = View.VISIBLE
                val tf = etLoginInput?.typeface
                etLoginInput?.inputType = InputType.TYPE_CLASS_PHONE
                etLoginInput?.typeface = tf
                tvMode?.visibility = View.VISIBLE
                loginInputEngine?.isModeSwitchingEnabled = true
                loginInputEngine?.isSymbolPickerEnabled = true
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
            is AuthState.WaitOtherDeviceConfirmation -> {
                layoutInputContainer?.visibility = View.GONE
                layoutQrContainer?.visibility = View.VISIBLE
                btnSubmit.visibility = View.GONE
                btnUseSim?.visibility = View.GONE
                btnQrLogin?.visibility = View.GONE

                val qrBitmap = com.teleflip.app.ui.QrCodeGenerator.generateQrCodeBitmap(state.link)
                if (qrBitmap != null) {
                    ivQrCode?.setImageBitmap(qrBitmap)
                }

                if (state.errorMessage != null) {
                    val errorMessage = "⚠️ Error: ${state.errorMessage}"
                    tvStatus.text = errorMessage
                    tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_error))
                    tvStatus.announceForAccessibility(errorMessage)
                } else {
                    tvStatus.text = "Scan QR code with Telegram on your main phone"
                    tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
                }
            }
            is AuthState.WaitCode -> {
                layoutInputContainer?.visibility = View.VISIBLE
                layoutQrContainer?.visibility = View.GONE
                btnSubmit.visibility = View.VISIBLE
                btnQrLogin?.visibility = View.GONE
                val tf = etLoginInput?.typeface
                etLoginInput?.inputType = InputType.TYPE_CLASS_NUMBER
                etLoginInput?.typeface = tf
                tvMode?.visibility = View.GONE
                loginInputEngine?.isModeSwitchingEnabled = false
                loginInputEngine?.isSymbolPickerEnabled = false
                loginInputEngine?.setMode(com.teleflip.app.input.InputMode.NUMERIC)
                btnUseSim?.visibility = View.GONE
                tvLabel.text = "Verification Code (numbers only):"
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
                layoutInputContainer?.visibility = View.VISIBLE
                layoutQrContainer?.visibility = View.GONE
                btnSubmit.visibility = View.VISIBLE
                btnQrLogin?.visibility = View.GONE
                val tf = etLoginInput?.typeface
                etLoginInput?.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                etLoginInput?.typeface = tf
                tvMode?.visibility = View.VISIBLE
                loginInputEngine?.isModeSwitchingEnabled = true
                loginInputEngine?.isSymbolPickerEnabled = true
                loginInputEngine?.setMode(com.teleflip.app.input.InputMode.LOWER)
                btnUseSim?.visibility = View.GONE
                btnForgotPassword?.visibility = View.VISIBLE
                btnResetAccount?.visibility = View.VISIBLE

                btnSubmit.nextFocusDownId = R.id.btn_forgot_password
                btnForgotPassword?.nextFocusUpId = R.id.btn_login_submit
                btnForgotPassword?.nextFocusDownId = R.id.btn_reset_account
                btnResetAccount?.nextFocusUpId = R.id.btn_forgot_password
                btnResetAccount?.nextFocusDownId = R.id.btn_config_credentials
                btnConfig.nextFocusUpId = R.id.btn_reset_account

                btnForgotPassword?.setOnClickListener {
                    telegramManager.requestPasswordRecovery()
                }
                btnResetAccount?.setOnClickListener {
                    showAccountResetConfirmationDialog()
                }
                tvLabel.text = "2-Step Verification Password:"
                etLoginInput?.hint = "Cloud Password (letters, numbers, symbols)"
                btnSubmit.text = "Submit Password"
                if (state.errorMessage != null) {
                    val errorMessage = "⚠️ Error: ${state.errorMessage}"
                    tvStatus.text = errorMessage
                    tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_error))
                    tvStatus.announceForAccessibility(errorMessage)
                } else {
                    val statusText =
                        buildString {
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
                layoutInputContainer?.visibility = View.VISIBLE
                layoutQrContainer?.visibility = View.GONE
                btnSubmit.visibility = View.VISIBLE
                btnQrLogin?.visibility = View.GONE
                val tf = etLoginInput?.typeface
                etLoginInput?.inputType = InputType.TYPE_CLASS_NUMBER
                etLoginInput?.typeface = tf
                tvMode?.visibility = View.GONE
                loginInputEngine?.isModeSwitchingEnabled = false
                loginInputEngine?.isSymbolPickerEnabled = false
                loginInputEngine?.setMode(com.teleflip.app.input.InputMode.NUMERIC)
                btnUseSim?.visibility = View.GONE
                btnForgotPassword?.visibility = View.GONE
                btnResetAccount?.visibility = View.GONE

                btnSubmit.nextFocusDownId = R.id.btn_config_credentials
                btnConfig.nextFocusUpId = R.id.btn_login_submit

                tvLabel.text = "Email Recovery Code (numbers only):"
                etLoginInput?.hint = "123456"
                etLoginInput?.setText("")
                btnSubmit.text = "Submit Recovery Code"

                if (state.errorMessage != null) {
                    tvStatus.text = "⚠️ Error: ${state.errorMessage}"
                    tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_error))
                } else {
                    tvStatus.text =
                        if (state.recoveryEmailPattern.isNotBlank()) {
                            "Code sent to ${state.recoveryEmailPattern}"
                        } else {
                            "Enter code sent to recovery email"
                        }
                    tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
                }
            }
            is AuthState.Error -> {
                layoutInputContainer?.visibility = View.VISIBLE
                layoutQrContainer?.visibility = View.GONE
                btnSubmit.visibility = View.VISIBLE
                btnQrLogin?.visibility = View.GONE
                btnUseSim?.visibility = View.GONE
                btnSubmit.nextFocusDownId = R.id.btn_config_credentials
                btnConfig.nextFocusUpId = R.id.btn_login_submit
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
                    val hint =
                        when (telegramManager.authState.value) {
                            is AuthState.WaitCode -> "Please enter verification code"
                            is AuthState.WaitPassword -> "Please enter password"
                            is AuthState.WaitPasswordRecoveryCode -> "Please enter recovery code"
                            else -> "Please enter a phone number"
                        }
                    showToast(hint, true)
                }
            }
        }

        btnSubmit.setOnClickListener { doSubmit() }
        btnConfig.setOnClickListener { showSettingsScreen() }

        updateLoginConnectionState(telegramManager.connectionState.value)
        updateLoginProgressState(telegramManager.isAuthQueryInProgress)

        val isPasswordScreen = state is AuthState.WaitPassword
        softkeyManager.update(
            left = if (isPasswordScreen) "Symbols" else "Settings",
            center =
                when (state) {
                    is AuthState.WaitCode, is AuthState.WaitPasswordRecoveryCode -> "Verify"
                    is AuthState.WaitPassword -> "Submit"
                    is AuthState.WaitOtherDeviceConfirmation -> "Phone Login"
                    else -> "Send"
                },
            right = "Exit",
            onLeft = {
                if (isPasswordScreen) {
                    showSymbolsDialog { symbol -> loginInputEngine?.insertSymbol(symbol) }
                } else {
                    showSettingsScreen()
                }
            },
            onCenter = {
                if (currentFocus?.id == R.id.btn_retry_connection) {
                    telegramManager.retryConnection()
                } else if (state is AuthState.WaitOtherDeviceConfirmation) {
                    telegramManager.showPhoneNumberLogin()
                } else {
                    doSubmit()
                }
            },
            onRight = { finish() },
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
            val err =
                when (currentAuth) {
                    is AuthState.WaitPhoneNumber -> currentAuth.errorMessage
                    is AuthState.WaitOtherDeviceConfirmation -> currentAuth.errorMessage
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
            val defaultText =
                when (telegramManager.authState.value) {
                    is AuthState.WaitCode -> "Verify Code"
                    is AuthState.WaitPassword -> "Submit Password"
                    is AuthState.WaitOtherDeviceConfirmation -> "Waiting for Scan..."
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

        findViewById<View>(prevId)?.nextFocusDownId = R.id.btn_config_credentials
        btnConfig.nextFocusUpId = prevId
    }

    private fun showAccountResetConfirmationDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Reset Account?")
            .setMessage(
                "Warning: Resetting your account will permanently delete your session and cloud messages for this account. This action cannot be undone. Are you sure you want to reset your account?",
            )
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
        val hasPhoneNumbers =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED
            } else {
                false
            }
        return hasPhoneState || hasPhoneNumbers
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun getDevicePhoneNumberApi33(): String? {
        try {
            val sm = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            if (sm != null) {
                try {
                    @Suppress("MissingPermission")
                    val activeList = sm.activeSubscriptionInfoList
                    if (!activeList.isNullOrEmpty()) {
                        for (sub in activeList) {
                            try {
                                @Suppress("MissingPermission", "NewApi", "InlinedApi")
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
                    org.telegram.messenger.FileLog.e(
                        "getDevicePhoneNumber API 33+ activeSubscriptionInfoList SecurityException: ${e.message}",
                    )
                } catch (e: Throwable) {
                    org.telegram.messenger.FileLog.e("getDevicePhoneNumber API 33+ activeSubscriptionInfoList failed: ${e.message}")
                }

                // Fallback to DEFAULT_SUBSCRIPTION_ID on API 33+
                try {
                    @Suppress("MissingPermission", "NewApi", "InlinedApi")
                    val raw = sm.getPhoneNumber(SubscriptionManager.DEFAULT_SUBSCRIPTION_ID)
                    val formatted = cleanAndFormatPhoneNumber(raw)
                    if (formatted != null) return formatted
                } catch (e: SecurityException) {
                    org.telegram.messenger.FileLog.e(
                        "getDevicePhoneNumber API 33+ DEFAULT_SUBSCRIPTION_ID SecurityException: ${e.message}",
                    )
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

    @android.annotation.SuppressLint("NewApi", "InlinedApi")
    private fun getDevicePhoneNumber(): String? {
        if (!hasTelephonyPermission()) {
            return null
        }

        // Branch 1: API Level 33 and newer (Android 13+ / TIRAMISU+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return getDevicePhoneNumberApi33()
        }

        // Branch 2: API Level 30 to 32 (Android 11 to Android 12L / R..S_V2)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
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
        navigateTo(Screen.SETTINGS) {
            renderSettingsScreen()
        }
    }

    private fun renderSettingsScreen() {
        commitActiveInputEngines()
        currentScreen = Screen.SETTINGS
        mainContainer.removeAllViews()

        val view = LayoutInflater.from(this).inflate(R.layout.view_settings, mainContainer, true)

        val etId = view.findViewById<EditText>(R.id.et_api_id)
        val etHash = view.findViewById<EditText>(R.id.et_api_hash)
        val spTimeout = view.findViewById<Spinner>(R.id.sp_keypad_timeout)
        val btnBgPush = view.findViewById<Button>(R.id.btn_toggle_bg_notifications)
        val btnEditProfile = view.findViewById<Button>(R.id.btn_edit_profile)
        view.findViewById<Button>(R.id.btn_telegram_privacy)?.setOnClickListener { featureMenus.privacy() }
        view.findViewById<Button>(R.id.btn_two_step)?.setOnClickListener { featureMenus.twoStep() }
        val btnActiveSessions = view.findViewById<Button>(R.id.btn_active_sessions)
        val btnBlockedUsers = view.findViewById<Button>(R.id.btn_blocked_users)
        val btnPasscodeLock = view.findViewById<Button>(R.id.btn_passcode_lock)
        val btnProxySettings = view.findViewById<Button>(R.id.btn_proxy_settings)
        val btnStorageLocation = view.findViewById<Button>(R.id.btn_storage_location)
        val btnAutoDownload = view.findViewById<Button>(R.id.btn_auto_download)
        val btnKeepMedia = view.findViewById<Button>(R.id.btn_keep_media)
        val btnSaveToGallery = view.findViewById<Button>(R.id.btn_save_to_gallery)
        val btnClearCache = view.findViewById<Button>(R.id.btn_clear_cache)
        val btnSave = view.findViewById<Button>(R.id.btn_save_config)
        val btnLogout = view.findViewById<Button>(R.id.btn_logout)
        val btnBack = view.findViewById<Button>(R.id.btn_back_to_chats)

        val tvUser = view.findViewById<TextView>(R.id.tv_settings_user)
        val tvStatus = view.findViewById<TextView>(R.id.tv_settings_status)

        telegramManager.loadCurrentUser()
        lifecycleScope.launch {
            telegramManager.currentUser.collectLatest { profile ->
                if (profile != null) {
                    val name = "${profile.firstName} ${profile.lastName}".trim().ifBlank { "User #${profile.userId}" }
                    val phone = if (profile.phoneNumber.isNotBlank()) " (${profile.phoneNumber})" else ""
                    tvUser?.text = "Account: $name$phone"
                    val handle = if (profile.username.isNotBlank()) "@${profile.username} • " else ""
                    tvStatus?.text = "${handle}Bio: ${profile.bio.ifBlank { "None" }}"
                }
            }
        }

        fun updateStorageLabels() {
            val currentStorage = com.teleflip.app.storage.StorageManager.getSelectedStorageType(this)
            val storageLabel =
                if (currentStorage == com.teleflip.app.storage.StorageLocationType.SD_CARD) {
                    "MicroSD Card"
                } else {
                    "Internal Storage"
                }
            btnStorageLocation?.text = "💾 Storage: $storageLabel"

            val autoMode = com.teleflip.app.storage.StorageManager.getAutoDownloadMode(this)
            btnAutoDownload?.text = "📥 Auto-Download: ${autoMode.label}"

            val keepMedia = com.teleflip.app.storage.StorageManager.getKeepMediaDuration(this)
            btnKeepMedia?.text = "⏳ Keep Media: ${keepMedia.label}"

            val saveEnabled = com.teleflip.app.storage.StorageManager.isSaveToDownloadsEnabled(this)
            btnSaveToGallery?.text = "📁 Save to Downloads: ${if (saveEnabled) "ON" else "OFF"}"
        }
        updateStorageLabels()

        btnEditProfile?.setOnClickListener { showEditProfileMenu() }
        btnActiveSessions?.setOnClickListener { showActiveSessionsMenu() }
        btnBlockedUsers?.setOnClickListener { showBlockedUsersDialog() }

        fun updatePasscodeLabel() {
            val isEnabled = PasscodeManager.isEnabled(this)
            val timeoutLabel = PasscodeManager.getAutoLockLabel(this)
            btnPasscodeLock?.text = if (isEnabled) "🔒 App Passcode: ON ($timeoutLabel)" else "🔒 App Passcode Lock: OFF"
        }
        updatePasscodeLabel()
        btnPasscodeLock?.setOnClickListener {
            showPasscodeSettingsDialog {
                updatePasscodeLabel()
            }
        }

        fun updateProxyLabel() {
            val enabledProxy = telegramManager.getEnabledProxy()
            btnProxySettings?.text =
                if (enabledProxy != null) {
                    "🌐 Proxy: [${enabledProxy.type.name}] ${enabledProxy.server}:${enabledProxy.port}"
                } else {
                    "🌐 Proxy Settings: Disabled"
                }
        }
        updateProxyLabel()
        btnProxySettings?.setOnClickListener {
            com.teleflip.app.ui.ProxySettingsDialog(this, telegramManager, telegramManager.keypadTimeoutMs) {
                updateProxyLabel()
            }.show()
        }

        btnStorageLocation?.setOnClickListener { showStorageLocationDialog { updateStorageLabels() } }
        btnAutoDownload?.setOnClickListener { showAutoDownloadDialog { updateStorageLabels() } }
        btnKeepMedia?.setOnClickListener { showKeepMediaDialog { updateStorageLabels() } }
        btnSaveToGallery?.setOnClickListener {
            val newState = !com.teleflip.app.storage.StorageManager.isSaveToDownloadsEnabled(this)
            com.teleflip.app.storage.StorageManager.setSaveToDownloadsEnabled(this, newState)
            updateStorageLabels()
            showToast(if (newState) "Automatic save to downloads enabled" else "Automatic save to downloads disabled")
        }
        btnClearCache?.setOnClickListener {
            telegramManager.clearMediaCache { _, freed ->
                val mb = freed / BYTES_PER_MB
                showToast("Cleared media cache ($mb MB freed)")
            }
        }

        fun updateBgPushLabel(enabled: Boolean) {
            btnBgPush?.text =
                if (enabled) {
                    "🔔 Closed-Flip Background Push: ON"
                } else {
                    "🔕 Closed-Flip Background Push: OFF"
                }
        }
        updateBgPushLabel(telegramManager.backgroundPushEnabled)
        btnBgPush?.setOnClickListener {
            val newState = !telegramManager.backgroundPushEnabled
            telegramManager.backgroundPushEnabled = newState
            updateBgPushLabel(newState)
            TeleFlipNotificationService.startOrStop(this, newState)
            showToast(if (newState) "Closed-flip background notifications enabled" else "Background notifications disabled")
        }

        etId.setText(telegramManager.apiId.toString())
        etHash.setText(telegramManager.apiHash)

        val timeoutValues = listOf(300L, 500L, 900L)
        val timeoutLabels = listOf("300 ms (Fast)", "500 ms (Normal)", "900 ms (Slow)")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, timeoutLabels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spTimeout.adapter = adapter

        val currentTimeout = telegramManager.keypadTimeoutMs
        val initialIndex =
            when (currentTimeout) {
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
                telegramManager.retryConnection()
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
            },
        )

        etId.requestFocus()
        updateActiveCallOverlay()
    }

    // =========================================================================
    // SCREEN 4: IN-CALL SCREEN (FLIP PHONE OPTIMIZED)
    // =========================================================================
    private fun showInCallScreen(call: ActiveCall) {
        navigateTo(Screen.IN_CALL) {
            renderInCallScreen(call)
        }
    }

    private fun renderInCallScreen(call: ActiveCall) {
        commitActiveInputEngines()
        currentScreen = Screen.IN_CALL
        mainContainer.removeAllViews()

        findViewById<View>(R.id.layout_active_call_overlay)?.visibility = View.GONE

        val view = LayoutInflater.from(this).inflate(R.layout.view_in_call, mainContainer, true)

        val ivAvatar = view.findViewById<ImageView>(R.id.iv_call_avatar)
        val tvAvatar = view.findViewById<TextView>(R.id.tv_call_avatar)
        val tvName = view.findViewById<TextView>(R.id.tv_call_name)
        val btnAccept = view.findViewById<Button>(R.id.btn_call_accept)
        val btnMute = view.findViewById<Button>(R.id.btn_call_mute)
        val btnSpeaker = view.findViewById<Button>(R.id.btn_call_speaker)
        val btnEnd = view.findViewById<Button>(R.id.btn_call_end)

        tvName.text = call.userName
        val avatarLetters = if (call.userName.isNotBlank()) call.userName.take(2).uppercase() else "TG"
        val chat = telegramManager.chats.value.find { it.id == call.userId }
        ivAvatar.loadCircularAvatarOrFallback(chat?.avatarPath, chat?.avatarLetters ?: avatarLetters, tvAvatar)

        btnAccept.setOnClickListener {
            acceptAudioCall(call.callId)
        }
        btnMute.setOnClickListener {
            toggleInCallMute()
        }
        btnSpeaker.setOnClickListener {
            toggleInCallSpeaker()
        }
        btnEnd.setOnClickListener {
            telegramManager.discardCall(call.callId, callDurationSec)
        }

        listOf(btnAccept, btnMute, btnSpeaker, btnEnd).forEach { button ->
            button.setOnFocusChangeListener { _, focused ->
                if (focused) telegramManager.activeCall.value?.let { updateInCallUI(it) }
            }
        }
        updateInCallUI(call)
        if (!call.isOutgoing && call.state == CallState.PENDING) btnAccept.requestFocus() else btnMute.requestFocus()
    }

    private fun updateInCallUI(call: ActiveCall) {
        callDurationSec = call.durationSec
        findViewById<TextView>(R.id.tv_call_encryption_badge)?.text = if (call.verificationEmojis.isEmpty()) "🔒 End-to-End Encrypted Telegram Call" else "🔒 ${call.verificationEmojis.joinToString(" ")}\nCompare these emoji with your contact"
        findViewById<View>(R.id.layout_active_call_overlay)?.visibility = View.GONE
        val tvStatus = findViewById<TextView>(R.id.tv_call_status) ?: return
        val tvMuteInd = findViewById<TextView>(R.id.tv_call_mute_indicator)
        val tvSpeakerInd = findViewById<TextView>(R.id.tv_call_speaker_indicator)
        val btnAccept = findViewById<Button>(R.id.btn_call_accept)
        val btnMute = findViewById<Button>(R.id.btn_call_mute)
        val btnSpeaker = findViewById<Button>(R.id.btn_call_speaker)

        val isIncomingPending = !call.isOutgoing && call.state == CallState.PENDING
        btnAccept?.visibility = if (isIncomingPending) View.VISIBLE else View.GONE

        tvMuteInd?.text = if (call.isMuted) "🎤 Mic: Muted" else "🎤 Mic: On"
        tvSpeakerInd?.text = if (call.isSpeakerOn) "🔊 Speaker: On" else "🔈 Earpiece"

        btnMute?.text = if (call.isMuted) "Unmute Mic" else "Mute Mic"
        btnSpeaker?.text = if (call.isSpeakerOn) "Earpiece" else "Speaker"

        when (call.state) {
            CallState.PENDING -> {
                tvStatus.text = if (call.isOutgoing) "Calling..." else "Incoming Telegram Call..."
                stopCallTimer()
            }
            CallState.EXCHANGING_KEYS -> {
                tvStatus.text = "Exchanging encryption keys..."
            }
            CallState.CONNECTING -> {
                tvStatus.text = "Connecting audio..."
            }
            CallState.READY -> {
                tvStatus.text = "In Call • ${formatCallDuration(call.durationSec)}"
            }
            CallState.HANGING_UP -> {
                tvStatus.text = "Hanging up..."
                stopCallTimer()
            }
            CallState.DISCARDED -> {
                tvStatus.text = "Call Ended (${formatCallDuration(callDurationSec)})"
                stopCallTimer()
            }
            CallState.ERROR -> {
                tvStatus.text = "Call Failed"
                stopCallTimer()
            }
        }

        val focusedButton = currentFocus as? Button
        val centerLabel =
            focusedButton?.text?.toString() ?: if (isIncomingPending) {
                "Speaker"
            } else if (call.isMuted) {
                "Unmute"
            } else {
                "Mute"
            }
        if (!isIncomingPending && btnAccept?.hasFocus() == true) btnMute?.requestFocus()
        if (isIncomingPending) {
            softkeyManager.update(
                left = "Accept",
                center = centerLabel,
                right = "Decline",
                onLeft = { acceptAudioCall(call.callId) },
                onCenter = { if (focusedButton != null) focusedButton.performClick() else toggleInCallSpeaker() },
                onRight = { telegramManager.discardCall(call.callId, 0) },
            )
        } else {
            softkeyManager.update(
                left = "Minimize",
                center = centerLabel,
                right = "End Call",
                onLeft = { minimizeCall() },
                onCenter = { if (focusedButton != null) focusedButton.performClick() else toggleInCallMute() },
                onRight = { telegramManager.discardCall(call.callId, call.durationSec) },
            )
        }
    }

    @VisibleForTesting
    internal fun setSpeakerphoneEnabled(
        audioManager: AudioManager,
        enabled: Boolean,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (enabled) {
                val speakerDevice =
                    audioManager.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }
                if (speakerDevice != null) {
                    audioManager.setCommunicationDevice(speakerDevice)
                }
            } else {
                audioManager.clearCommunicationDevice()
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = enabled
        }
    }

    @VisibleForTesting
    internal fun isSpeakerphoneEnabled(audioManager: AudioManager): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn
        }
    }

    private var pendingCallPermission: (() -> Unit)? = null

    private fun withCallMicrophone(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            pendingCallPermission = action
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_CALL_AUDIO_PERM)
        }
    }

    private fun initiateAudioCall(
        userId: Long,
        onResult: ((Boolean, String?) -> Unit)? = null,
    ) {
        withCallMicrophone {
            cancelActiveVoiceOperations()
            telegramManager.initiateTelegramCall(userId) { success, message ->
                if (!success) showToast(message ?: "Unable to start call", isError = true)
                onResult?.invoke(success, message)
            }
        }
    }

    private fun acceptAudioCall(callId: Int) {
        withCallMicrophone {
            cancelActiveVoiceOperations()
            telegramManager.acceptCall(callId) { success, message ->
                if (!success) showToast(message ?: "Unable to accept call", isError = true)
            }
        }
    }

    private fun toggleInCallMute() {
        val current = telegramManager.activeCall.value ?: return
        telegramManager.updateCallAudioState(isMuted = !current.isMuted)
    }

    private fun toggleInCallSpeaker() {
        val current = telegramManager.activeCall.value ?: return
        telegramManager.updateCallAudioState(isSpeakerOn = !current.isSpeakerOn)
    }

    private fun cleanupInCallAudio() {
        stopCallTimer()
        callDurationSec = 0
    }

    private fun stopCallTimer() {
        callTimerJob?.cancel()
        callTimerJob = null
    }

    private fun formatCallDuration(seconds: Int): String {
        val mins = seconds / 60
        val secs = seconds % 60
        return String.format(Locale.US, "%02d:%02d", mins, secs)
    }

    private fun minimizeCall() {
        if (currentScreen == Screen.IN_CALL) {
            if (activeChat != null) {
                openConversation(activeChat!!)
            } else {
                showChatListScreen()
            }
        }
    }

    private fun returnToCall() {
        val call = telegramManager.activeCall.value
        if (call != null && call.state != CallState.DISCARDED && call.state != CallState.ERROR) {
            showInCallScreen(call)
        }
    }

    private fun updateActiveCallOverlay() {
        val overlayView = findViewById<View>(R.id.layout_active_call_overlay) ?: return
        val call = telegramManager.activeCall.value

        if (call != null &&
            call.state != CallState.DISCARDED &&
            call.state != CallState.ERROR &&
            currentScreen != Screen.IN_CALL &&
            activeDialogCount <= 0 &&
            currentScreen != Screen.PASSCODE_LOCK &&
            currentScreen != Screen.LOGIN
        ) {
            overlayView.visibility = View.VISIBLE
            val tvName = overlayView.findViewById<TextView>(R.id.tv_overlay_name)
            val tvStatus = overlayView.findViewById<TextView>(R.id.tv_overlay_status)

            tvName?.text = call.userName
            val statusText =
                when (call.state) {
                    CallState.PENDING -> if (call.isOutgoing) "Calling..." else "Incoming Telegram Call..."
                    CallState.EXCHANGING_KEYS -> "Exchanging encryption keys..."
                    CallState.CONNECTING -> "Connecting audio..."
                    CallState.READY -> "In Call • ${formatCallDuration(call.durationSec)}"
                    CallState.HANGING_UP -> "Hanging up..."
                    CallState.DISCARDED -> "Call Ended"
                    CallState.ERROR -> "Call Failed"
                }
            tvStatus?.text = statusText

            overlayView.setOnClickListener {
                returnToCall()
            }

            softkeyManager.update(
                left = "Return to Call",
                center = if (call.isMuted) "Unmute" else "Mute",
                right = "End Call",
                onLeft = { returnToCall() },
                onCenter = { toggleInCallMute() },
                onRight = { telegramManager.discardCall(call.callId, callDurationSec) },
            )
        } else {
            overlayView.visibility = View.GONE
        }
    }

    private fun restoreCurrentScreenSoftkeys() {
        if (activeDialogCount > 0) return
        when (currentScreen) {
            Screen.CHAT_LIST -> {
                showChatListScreen()
            }
            Screen.CONVERSATION -> {
                activeChat?.let { restoreConversationSoftkeys(it) }
            }
            Screen.SETTINGS -> {
                showSettingsScreen()
            }
            Screen.PROFILE -> {
                activeChat?.let { showProfileScreen(it) }
            }
            else -> {}
        }
    }

    // =========================================================================
    // SCREEN 5: PROFILE & CHAT INFO (FLIP PHONE OPTIMIZED)
    // =========================================================================
    private fun showProfileScreen(chat: ChatModel) {
        navigateTo(Screen.PROFILE) {
            renderProfileScreen(chat)
        }
    }

    private fun renderProfileScreen(chat: ChatModel) {
        commitActiveInputEngines()
        currentScreen = Screen.PROFILE
        mainContainer.removeAllViews()

        val view = LayoutInflater.from(this).inflate(R.layout.view_profile, mainContainer, true)

        val ivAvatar = view.findViewById<ImageView>(R.id.iv_profile_avatar)
        val tvAvatar = view.findViewById<TextView>(R.id.tv_profile_avatar)
        val tvTitle = view.findViewById<TextView>(R.id.tv_profile_title)
        val tvStatus = view.findViewById<TextView>(R.id.tv_profile_status)
        val tvPremium = view.findViewById<TextView>(R.id.tv_profile_premium)

        val rowPhone = view.findViewById<View>(R.id.layout_profile_phone_row)
        val tvPhone = view.findViewById<TextView>(R.id.tv_profile_phone)
        val rowUsername = view.findViewById<View>(R.id.layout_profile_username_row)
        val tvUsername = view.findViewById<TextView>(R.id.tv_profile_username)
        val rowBio = view.findViewById<View>(R.id.layout_profile_bio_row)
        val tvBio = view.findViewById<TextView>(R.id.tv_profile_bio)
        val tvBioLabel = view.findViewById<TextView>(R.id.tv_profile_bio_label)

        val btnTgCall = view.findViewById<Button>(R.id.btn_profile_tg_call)
        val btnCellCall = view.findViewById<Button>(R.id.btn_profile_cell_call)
        val btnSecretChat = view.findViewById<Button>(R.id.btn_profile_secret_chat)
        val btnSelfDestruct = view.findViewById<Button>(R.id.btn_profile_self_destruct)
        val btnMembers = view.findViewById<Button>(R.id.btn_profile_members)
        val btnForumTopics = view.findViewById<Button>(R.id.btn_profile_forum_topics)
        val btnSendMsg = view.findViewById<Button>(R.id.btn_profile_send_msg)
        val btnToggleMute = view.findViewById<Button>(R.id.btn_profile_toggle_mute)
        val btnSearch = view.findViewById<Button>(R.id.btn_profile_search)
        val btnExportHistory = view.findViewById<Button>(R.id.btn_profile_export_history)

        btnExportHistory?.setOnClickListener {
            showChatExportFormatDialog(chat.id, chat.title)
        }

        if (chat.chatType == ChatType.DIRECT) {
            btnSecretChat?.visibility = if (chat.isSecretChat) View.GONE else View.VISIBLE
            btnSelfDestruct?.visibility = View.VISIBLE
            val ttlLabel = if (chat.selfDestructTimerSec > 0) "${chat.selfDestructTimerSec}s" else "Off"
            btnSelfDestruct?.text = "⏱️ Self-Destruct Timer: $ttlLabel"
            btnMembers?.visibility = View.GONE
            btnForumTopics?.visibility = View.GONE
        } else {
            btnSecretChat?.visibility = View.GONE
            btnSelfDestruct?.visibility = View.GONE
            btnMembers?.visibility = View.VISIBLE
            btnForumTopics?.visibility = View.VISIBLE
        }

        btnSecretChat?.setOnClickListener {
            val targetUserId = telegramManager.getDirectChatUserId(chat.id) ?: chat.id
            telegramManager.startSecretChat(chat.title, targetUserId) { secretChat, err ->
                if (secretChat != null) {
                    showToast("🔒 Secret Chat started with ${secretChat.title}")
                    openConversation(secretChat)
                } else {
                    showToast(err ?: "Unable to start Secret Chat", isError = true)
                }
            }
        }
        btnSelfDestruct?.setOnClickListener {
            showSelfDestructTimerMenu(chat)
        }
        btnMembers?.setOnClickListener {
            showGroupMembersMenu(chat)
        }
        btnForumTopics?.setOnClickListener {
            showForumTopicsMenu(chat)
        }

        tvTitle.text = chat.title
        tvPremium.visibility = if (chat.isPremium) View.VISIBLE else View.GONE
        ivAvatar.loadCircularAvatarOrFallback(chat.avatarPath, chat.avatarLetters, tvAvatar)

        if (chat.chatType != ChatType.DIRECT) {
            btnTgCall.visibility = View.GONE
            btnCellCall.visibility = View.GONE
            tvBioLabel.text = "Group / Channel Info"
        }

        softkeyManager.update(
            left = if (chat.chatType == ChatType.DIRECT) "Call" else "Select",
            center = "Select",
            right = "Back",
            onLeft = {
                if (chat.chatType == ChatType.DIRECT) {
                    val userId = telegramManager.getDirectChatUserId(chat.id) ?: chat.id
                    initiateAudioCall(userId)
                }
            },
            onCenter = {
                currentFocus?.performClick()
            },
            onRight = {
                if (activeChat != null && activeChat?.id == chat.id) {
                    openConversation(chat)
                } else {
                    showChatListScreen()
                }
            },
        )
        updateActiveCallOverlay()

        telegramManager.loadChatProfile(chat.id) { profile ->
            tvTitle.text = profile.title
            tvStatus.text = profile.statusText
            tvPremium.visibility = if (profile.isPremium) View.VISIBLE else View.GONE

            if (profile.phoneNumber.isNotBlank()) {
                rowPhone.visibility = View.VISIBLE
                btnCellCall.visibility = View.VISIBLE
                tvPhone.text = profile.phoneNumber
                val dialAction =
                    View.OnClickListener {
                        try {
                            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${profile.phoneNumber}")))
                        } catch (e: Exception) {
                            showToast("Could not open dialer: ${e.message}", true)
                        }
                    }
                rowPhone.setOnClickListener(dialAction)
                btnCellCall.setOnClickListener(dialAction)
            } else {
                rowPhone.visibility = View.GONE
                btnCellCall.visibility = View.GONE
            }

            if (profile.username.isNotBlank()) {
                rowUsername.visibility = View.VISIBLE
                tvUsername.text = profile.username
            } else {
                rowUsername.visibility = View.GONE
            }

            if (profile.bio.isNotBlank()) {
                rowBio.visibility = View.VISIBLE
                tvBio.text = profile.bio
            } else {
                rowBio.visibility = View.GONE
            }

            btnToggleMute.text = if (profile.isMuted) "🔔 Notifications: Muted" else "🔔 Notifications: Sound"
            btnToggleMute.setOnClickListener {
                showChatMuteDialog(profile.chatId, profile.isMuted, profile.title) { newMuted ->
                    btnToggleMute.text = if (newMuted) "🔔 Notifications: Muted" else "🔔 Notifications: Sound"
                }
            }

            val btnBlock = view.findViewById<Button>(R.id.btn_profile_block_user)
            if (chat.chatType == ChatType.DIRECT) {
                btnBlock?.visibility = View.VISIBLE
                btnBlock?.setOnClickListener {
                    val targetUserId = telegramManager.getDirectChatUserId(chat.id) ?: chat.id
                    confirmBlockUser(targetUserId, chat.title)
                }
            } else {
                btnBlock?.visibility = View.GONE
            }

            btnTgCall.setOnClickListener {
                val userId = telegramManager.getDirectChatUserId(profile.chatId) ?: profile.chatId
                initiateAudioCall(userId)
            }

            btnSendMsg.setOnClickListener {
                openConversation(chat)
            }

            btnSearch.setOnClickListener {
                showSearchDialog()
            }
        }

        btnSendMsg?.post {
            btnSendMsg.requestFocus()
        }
    }

    // =========================================================================
    // HARDWARE KEYPAD & D-PAD EVENT ROUTING
    // =========================================================================
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (keyCode == KeyEvent.KEYCODE_CALL && event.action == KeyEvent.ACTION_UP) return true
        if (keyCode == KeyEvent.KEYCODE_CALL && event.repeatCount > 0) return true
        if (keyCode == KeyEvent.KEYCODE_HEADSETHOOK && telegramManager.hasOngoingCall) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                val call = telegramManager.activeCall.value
                if (call != null && !call.isOutgoing && call.state == CallState.PENDING) {
                    acceptAudioCall(call.callId)
                } else {
                    telegramManager.endCurrentCall()
                }
            }
            return true
        }

        // Top Left hardware softkey always triggers softkey action
        if (SoftkeyUtils.isSoftLeftKey(keyCode)) {
            if (softkeyManager.handleKeyEvent(keyCode, event)) return true
        }

        // Top Right hardware softkey handling (isolated from KEYCODE_BACK)
        if (SoftkeyUtils.isSoftRightKey(keyCode)) {
            if (softkeyManager.handleKeyEvent(keyCode, event)) return true
        }

        // Passcode Unlock Screen Key Routing
        if (currentScreen == Screen.PASSCODE_LOCK && event.action == KeyEvent.ACTION_DOWN) {
            if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
                val digit = (keyCode - KeyEvent.KEYCODE_0).toString()
                if (passcodeEntry.length < PASSCODE_PIN_LENGTH) {
                    passcodeEntry += digit
                    updatePasscodeDotsDisplay()
                    if (passcodeEntry.length == PASSCODE_PIN_LENGTH) {
                        attemptPasscodeUnlock()
                    }
                }
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_DEL) {
                if (passcodeEntry.isNotEmpty()) {
                    passcodeEntry = passcodeEntry.dropLast(1)
                    updatePasscodeDotsDisplay()
                }
                return true
            }
            if (SoftkeyUtils.isCenterKey(keyCode)) {
                if (passcodeEntry.length == PASSCODE_PIN_LENGTH) {
                    attemptPasscodeUnlock()
                    return true
                }
            }
        }

        // Dedicated Call button: Start call in direct chat or accept if incoming call
        if (keyCode == KeyEvent.KEYCODE_CALL && event.action == KeyEvent.ACTION_DOWN) {
            val call = telegramManager.activeCall.value
            if (call != null && call.state != CallState.DISCARDED && call.state != CallState.ERROR) {
                if (!call.isOutgoing && call.state == CallState.PENDING) {
                    acceptAudioCall(call.callId)
                    return true
                } else if (currentScreen != Screen.IN_CALL) {
                    returnToCall()
                    return true
                }
                return true
            }
            if (currentScreen == Screen.PROFILE) {
                activeChat?.let { chat ->
                    if (chat.chatType == ChatType.DIRECT) {
                        val userId = telegramManager.getDirectChatUserId(chat.id) ?: chat.id
                        initiateAudioCall(userId)
                        return true
                    }
                }
            } else if (currentScreen == Screen.CONVERSATION) {
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

        // Dedicated End Call button
        if (keyCode == KeyEvent.KEYCODE_ENDCALL && event.action == KeyEvent.ACTION_DOWN) {
            if (telegramManager.hasOngoingCall) {
                telegramManager.endCurrentCall()
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

        // In Conversation: D-Pad navigation between message list and compose input
        if (currentScreen == Screen.CONVERSATION && event.action == KeyEvent.ACTION_DOWN) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP && etComposeInput?.hasFocus() == true) {
                val cursor = etComposeInput?.selectionStart ?: 0
                val isAtTopLine = (etComposeInput?.layout?.getLineForOffset(cursor) ?: 0) == 0
                if (isAtTopLine) {
                    val count = messageAdapter?.itemCount ?: 0
                    if (count > 0) {
                        val targetPos = count - 1
                        rvMessages?.scrollToPosition(targetPos)
                        rvMessages?.post {
                            rvMessages?.findViewHolderForAdapterPosition(targetPos)?.itemView?.requestFocus()
                        }
                        return true
                    }
                }
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                val lastPos = (messageAdapter?.itemCount ?: 0) - 1
                if (lastPos >= 0) {
                    val focused = currentFocus
                    if (focused != null) {
                        val vh = rvMessages?.findContainingViewHolder(focused)
                        if (vh != null && vh.adapterPosition == lastPos) {
                            etComposeInput?.requestFocus()
                            return true
                        }
                    }
                }
            }
        }

        // In Conversation: Route keypad keys (digits, *, #) to compose box engine even if scrolling messages
        if (currentScreen == Screen.CONVERSATION) {
            val isComposeInputKey =
                (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) ||
                    keyCode == KeyEvent.KEYCODE_STAR ||
                    keyCode == KeyEvent.KEYCODE_POUND ||
                    (etComposeInput?.hasFocus() == true && (keyCode == KeyEvent.KEYCODE_DEL || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT))
            if (isComposeInputKey) {
                if (etComposeInput?.hasFocus() != true) {
                    etComposeInput?.requestFocus()
                }
                if (composeInputEngine?.handleKeyEvent(keyCode, event) == true) {
                    return true
                }
            }
        }

        // In Login: Route keypad keys to login input engine
        if (currentScreen == Screen.LOGIN) {
            val isInputKey =
                (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) ||
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
                            val targetTabId =
                                when {
                                    isArchivedTabSelected -> R.id.tab_archived
                                    isFoldersTabSelected -> R.id.tab_folders
                                    currentChatTypeFilter == ChatType.DIRECT -> R.id.tab_direct
                                    currentChatTypeFilter == ChatType.GROUP -> R.id.tab_groups
                                    currentChatTypeFilter == ChatType.CHANNEL -> R.id.tab_channels
                                    currentChatTypeFilter == ChatType.BOT -> R.id.tab_bots
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
        val totalTabs = 7
        val currentIndex =
            when {
                isArchivedTabSelected -> 6
                isFoldersTabSelected -> 5
                currentChatTypeFilter == ChatType.BOT -> 4
                currentChatTypeFilter == ChatType.CHANNEL -> 3
                currentChatTypeFilter == ChatType.GROUP -> 2
                currentChatTypeFilter == ChatType.DIRECT -> 1
                else -> 0
            }
        val nextIndex = (currentIndex + direction + totalTabs) % totalTabs
        when (nextIndex) {
            0 -> applyChatFilterAndDebounce(null)
            1 -> applyChatFilterAndDebounce(ChatType.DIRECT)
            2 -> applyChatFilterAndDebounce(ChatType.GROUP)
            3 -> applyChatFilterAndDebounce(ChatType.CHANNEL)
            4 -> applyChatFilterAndDebounce(ChatType.BOT)
            5 -> applyChatFilterAndDebounce(null, foldersMode = true)
            6 -> applyChatFilterAndDebounce(null, archivedMode = true)
        }
    }

    @Deprecated("Deprecated in Java")
    private fun pickPhotoAlbum(chat: ChatModel) {
        commitActiveInputEngines()
        val intent =
            Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        pendingAlbumChatId = chat.id
        pendingAlbumCaption = etComposeInput?.text?.toString().orEmpty()
        pendingAlbumReplyId = activeReplyMessage?.id ?: 0
        try {
            startActivityForResult(intent, REQ_PICK_ALBUM)
        } catch (_: android.content.ActivityNotFoundException) {
            pendingAlbumChatId = null
            showToast("No image picker is installed", true)
        }
    }

    private fun receivePhotoAlbum(
        resultCode: Int,
        data: Intent?,
    ) {
        val chatId = pendingAlbumChatId ?: return
        val caption = pendingAlbumCaption
        val replyId = pendingAlbumReplyId
        pendingAlbumChatId = null
        pendingAlbumCaption = ""
        pendingAlbumReplyId = 0
        if (resultCode != RESULT_OK || data == null) return
        val uris = data.clipData?.let { clip -> (0 until clip.itemCount).map { clip.getItemAt(it).uri } } ?: listOfNotNull(data.data)
        if (uris.size !in 2..10) {
            showToast("Select 2–10 photos for an album", true)
            return
        }
        lifecycleScope.launch {
            val files = mutableListOf<File>()
            try {
                withContext(Dispatchers.IO) {
                    uris.forEach { uri ->
                        val mime = contentResolver.getType(uri).orEmpty()
                        require(mime.startsWith("image/")) { "Choose image files only" }
                        val file = File.createTempFile("album_", ".image", cacheDir)
                        files.add(file)
                        val stream = contentResolver.openInputStream(uri) ?: error("Cannot read selected photo")
                        stream.use { source -> file.outputStream().use { target -> source.copyTo(target) } }
                        require(file.length() > 0) { "Selected photo is empty" }
                    }
                }
                telegramManager.features.sendAlbum(chatId, files.map { it.absolutePath }, caption, replyId) { result ->
                    if (result.isFailure) files.forEach { it.delete() }
                    runOnUiThread {
                        if (isDestroyed || isFinishing) return@runOnUiThread
                        if (result.isSuccess) {
                            showToast("Album queued for sending")
                            if (activeChat?.id == chatId && etComposeInput?.text?.toString() == caption && (activeReplyMessage?.id ?: 0L) == replyId) {
                                etComposeInput?.setText("")
                                clearReplyOrEditMode()
                            }
                        } else {
                            showToast(result.exceptionOrNull()?.message ?: "Album could not be sent", true)
                        }
                    }
                }
            } catch (error: Exception) {
                files.forEach { it.delete() }
                if (error is kotlinx.coroutines.CancellationException) throw error
                showToast(error.message ?: "Could not prepare album", true)
            }
        }
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_ALBUM) {
            receivePhotoAlbum(resultCode, data)
            return
        }
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data ?: return
        val chat = activeChat ?: return
        try {
            val ext =
                when (requestCode) {
                    REQ_PICK_STICKER -> "webp"
                    REQ_PICK_PHOTO -> "jpg"
                    REQ_PICK_VIDEO -> "mp4"
                    else -> "bin"
                }
            val tempFile = File(cacheDir, "upload_${System.currentTimeMillis()}.$ext")
            contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            if (tempFile.exists() && tempFile.length() > 0L) {
                val caption = etComposeInput?.text?.toString()?.trim().orEmpty()
                when (requestCode) {
                    REQ_PICK_STICKER -> {
                        telegramManager.sendLocalSticker(chat.id, tempFile.absolutePath)
                    }
                    REQ_PICK_PHOTO -> {
                        telegramManager.sendPhoto(chat.id, tempFile.absolutePath, caption)
                        showToast("Sending photo…")
                    }
                    REQ_PICK_VIDEO -> {
                        telegramManager.sendVideo(chat.id, tempFile.absolutePath, 0, caption)
                        showToast("Sending video…")
                    }
                    else -> {
                        telegramManager.sendDocument(chat.id, tempFile.absolutePath, caption)
                        showToast("Sending file…")
                    }
                }
                if (caption.isNotEmpty()) {
                    etComposeInput?.setText("")
                }
            }
        } catch (e: Exception) {
            showToast("Failed to attach file: ${e.message}", isError = true)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CALL_AUDIO_PERM) {
            val action = pendingCallPermission
            pendingCallPermission = null
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                action?.invoke()
            } else {
                showToast("Microphone permission is required for calls", isError = true)
            }
        }
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
