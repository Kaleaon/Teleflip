package com.teleflip.app.ui

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.teleflip.app.R
import com.teleflip.app.input.KeypadInputEngine
import com.teleflip.app.model.ChatModel
import com.teleflip.app.telegram.TelegramManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SearchDialog(
    context: Context,
    private val telegramManager: TelegramManager,
    private val onChatSelected: (ChatModel) -> Unit,
    private val onMessageSelected: (chatId: Long, messageId: Long) -> Unit,
) : BaseDialog(context) {
    enum class SearchTab {
        CHATS,
        CONTACTS,
        MESSAGES,
    }

    internal var currentTab: SearchTab = SearchTab.CHATS

    private lateinit var etSearchInput: EditText
    private lateinit var tvInputMode: TextView
    private lateinit var tabChats: TextView
    private lateinit var tabContacts: TextView
    private lateinit var tabMessages: TextView
    private lateinit var rvResults: RecyclerView
    private lateinit var layoutEmpty: View
    private lateinit var tvEmptyTitle: TextView
    private lateinit var progressBar: ProgressBar

    private var keypadEngine: KeypadInputEngine? = null

    private var chatAdapter: ChatListAdapter? = null
    private var messageSearchAdapter: MessageSearchResultAdapter? = null

    private var currentQuery: String = ""
    private var messageNextOffset: String = ""
    private var isMessageLoading: Boolean = false
    private val maxResultsLimit = 50

    private var searchJob: Job? = null
    private var dialogScope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var searchSequence: Long = 0L

    companion object {
        private const val DEBOUNCE_DELAY_MS = 300L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_search)

        window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
        )

        etSearchInput = findViewById(R.id.et_search_input)
        tvInputMode = findViewById(R.id.tv_search_input_mode)
        tabChats = findViewById(R.id.tab_search_chats)
        tabContacts = findViewById(R.id.tab_search_contacts)
        tabMessages = findViewById(R.id.tab_search_messages)
        rvResults = findViewById(R.id.rv_search_results)
        layoutEmpty = findViewById(R.id.layout_search_empty)
        tvEmptyTitle = findViewById(R.id.tv_search_empty_title)
        progressBar = findViewById(R.id.progress_search)

        keypadEngine =
            KeypadInputEngine(
                editText = etSearchInput,
                modeIndicator = tvInputMode,
                onSymbolRequested = {
                    SymbolsDialog(context) { sym -> keypadEngine?.insertSymbol(sym) }.show()
                },
            )

        rvResults.layoutManager = LinearLayoutManager(context)

        chatAdapter =
            ChatListAdapter(
                onChatSelected = { chat ->
                    dismiss()
                    onChatSelected(chat)
                },
                onChatAction = { actionId, chat ->
                    if (actionId == com.teleflip.app.R.id.action_chat_open) {
                        dismiss()
                        onChatSelected(chat)
                        true
                    } else false
                },
            )

        messageSearchAdapter =
            MessageSearchResultAdapter(
                onItemClick = { result ->
                    dismiss()
                    onMessageSelected(result.chatId, result.messageId)
                },
                onSearchResultAccessibilityAction = { actionId, result ->
                    if (actionId == com.teleflip.app.R.id.action_search_jump) {
                        dismiss()
                        onMessageSelected(result.chatId, result.messageId)
                        true
                    } else false
                },
            )

        setupTabs()
        setupSearchInput()
        setupPagination()

        selectTab(SearchTab.CHATS)

        etSearchInput.requestFocus()
    }

    private fun setupTabs() {
        tabChats.setOnClickListener { selectTab(SearchTab.CHATS) }
        tabContacts.setOnClickListener { selectTab(SearchTab.CONTACTS) }
        tabMessages.setOnClickListener { selectTab(SearchTab.MESSAGES) }
    }

    fun selectTab(tab: SearchTab) {
        currentTab = tab
        tabChats.isSelected = (tab == SearchTab.CHATS)
        tabContacts.isSelected = (tab == SearchTab.CONTACTS)
        tabMessages.isSelected = (tab == SearchTab.MESSAGES)

        when (tab) {
            SearchTab.CHATS, SearchTab.CONTACTS -> {
                rvResults.adapter = chatAdapter
            }
            SearchTab.MESSAGES -> {
                rvResults.adapter = messageSearchAdapter
            }
        }

        searchJob?.cancel()
        val requestId = ++searchSequence
        performSearch(currentQuery.trim(), requestId)
    }

    private fun setupSearchInput() {
        etSearchInput.addTextChangedListener(
            object : TextWatcher {
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
                    val text = s?.toString() ?: ""
                    if (text != currentQuery) {
                        currentQuery = text
                        onQueryChanged(text)
                    }
                }

                override fun afterTextChanged(s: Editable?) {}
            },
        )
    }

    private fun onQueryChanged(query: String) {
        searchJob?.cancel()
        val requestId = ++searchSequence
        val trimmed = query.trim()

        if (trimmed.isEmpty()) {
            progressBar.visibility = View.GONE
            layoutEmpty.visibility = View.VISIBLE
            tvEmptyTitle.text = "Type a keyword to search"
            chatAdapter?.submitList(emptyList())
            messageSearchAdapter?.submitList(emptyList())
            return
        }

        searchJob =
            dialogScope.launch {
                delay(DEBOUNCE_DELAY_MS)
                performSearch(trimmed, requestId)
            }
    }

    private fun setupPagination() {
        rvResults.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(
                    recyclerView: RecyclerView,
                    dx: Int,
                    dy: Int,
                ) {
                    if (dy > 0 && currentTab == SearchTab.MESSAGES) {
                        val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                        val visibleItemCount = lm.childCount
                        val totalItemCount = lm.itemCount
                        val firstVisibleItem = lm.findFirstVisibleItemPosition()

                        if (!isMessageLoading && messageNextOffset.isNotEmpty() && totalItemCount < maxResultsLimit) {
                            if (visibleItemCount + firstVisibleItem >= totalItemCount - 2) {
                                loadMoreMessageResults()
                            }
                        }
                    }
                }
            },
        )
    }

    private fun performSearch(query: String) {
        searchJob?.cancel()
        val requestId = ++searchSequence
        performSearch(query.trim(), requestId)
    }

    private fun performSearch(
        trimmed: String,
        requestId: Long,
    ) {
        if (trimmed.isEmpty()) {
            progressBar.visibility = View.GONE
            layoutEmpty.visibility = View.VISIBLE
            tvEmptyTitle.text = "Type a keyword to search"
            chatAdapter?.submitList(emptyList())
            messageSearchAdapter?.submitList(emptyList())
            return
        }

        progressBar.visibility = View.VISIBLE
        layoutEmpty.visibility = View.GONE

        when (currentTab) {
            SearchTab.CHATS -> {
                telegramManager.searchChats(trimmed) { results ->
                    if (requestId != searchSequence || trimmed != currentQuery.trim()) return@searchChats
                    progressBar.visibility = View.GONE
                    chatAdapter?.submitList(results)
                    if (results.isEmpty()) {
                        layoutEmpty.visibility = View.VISIBLE
                        tvEmptyTitle.text = "No chats found for \"$trimmed\""
                    } else {
                        layoutEmpty.visibility = View.GONE
                    }
                }
            }
            SearchTab.CONTACTS -> {
                telegramManager.searchContacts(trimmed) { results ->
                    if (requestId != searchSequence || trimmed != currentQuery.trim()) return@searchContacts
                    progressBar.visibility = View.GONE
                    chatAdapter?.submitList(results)
                    if (results.isEmpty()) {
                        layoutEmpty.visibility = View.VISIBLE
                        tvEmptyTitle.text = "No contacts found for \"$trimmed\""
                    } else {
                        layoutEmpty.visibility = View.GONE
                    }
                }
            }
            SearchTab.MESSAGES -> {
                isMessageLoading = true
                messageNextOffset = ""
                telegramManager.searchMessages(trimmed, offset = "", limit = 20) { _, results, nextOffset ->
                    if (requestId != searchSequence || trimmed != currentQuery.trim()) return@searchMessages
                    isMessageLoading = false
                    messageNextOffset = nextOffset
                    progressBar.visibility = View.GONE
                    val truncated = results.take(maxResultsLimit)
                    messageSearchAdapter?.submitList(truncated)
                    if (truncated.isEmpty()) {
                        layoutEmpty.visibility = View.VISIBLE
                        tvEmptyTitle.text = "No messages found for \"$trimmed\""
                    } else {
                        layoutEmpty.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun loadMoreMessageResults() {
        if (isMessageLoading || messageNextOffset.isEmpty() || currentQuery.isBlank()) return
        val currentCount = messageSearchAdapter?.itemCount ?: 0
        if (currentCount >= maxResultsLimit) return

        isMessageLoading = true
        val limitToFetch = (maxResultsLimit - currentCount).coerceAtMost(20)
        val requestId = searchSequence
        val trimmed = currentQuery.trim()

        telegramManager.searchMessages(trimmed, offset = messageNextOffset, limit = limitToFetch) { _, results, nextOffset ->
            if (requestId != searchSequence || trimmed != currentQuery.trim()) return@searchMessages
            isMessageLoading = false
            messageNextOffset = nextOffset
            val remainingSpace = maxResultsLimit - (messageSearchAdapter?.itemCount ?: 0)
            if (remainingSpace > 0 && results.isNotEmpty()) {
                val itemsToAdd = results.take(remainingSpace)
                messageSearchAdapter?.addMoreItems(itemsToAdd)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        searchJob?.cancel()
        dialogScope.cancel()
    }

    override fun dismiss() {
        searchJob?.cancel()
        dialogScope.cancel()
        super.dismiss()
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_SOFT_RIGHT) {
            dismiss()
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_SOFT_LEFT) {
            etSearchInput.setText("")
            return true
        }

        if (etSearchInput.hasFocus()) {
            val isInputKey =
                (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) ||
                    keyCode == KeyEvent.KEYCODE_STAR ||
                    keyCode == KeyEvent.KEYCODE_POUND ||
                    keyCode == KeyEvent.KEYCODE_DEL ||
                    keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
            if (isInputKey && keypadEngine?.handleKeyEvent(keyCode, event) == true) {
                return true
            }

            keypadEngine?.commitCurrentChar()

            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                if ((rvResults.adapter?.itemCount ?: 0) > 0) {
                    rvResults.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                    return true
                }
            }

            return super.onKeyDown(keyCode, event)
        } else {
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                val tabs = SearchTab.values()
                val prevIndex = (currentTab.ordinal - 1 + tabs.size) % tabs.size
                selectTab(tabs[prevIndex])
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                val tabs = SearchTab.values()
                val nextIndex = (currentTab.ordinal + 1) % tabs.size
                selectTab(tabs[nextIndex])
                return true
            }

            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                val focused = currentFocus
                if (focused != null && focused !== etSearchInput && focused !== tabChats && focused !== tabContacts && focused !== tabMessages) {
                    val itemView = rvResults.findContainingItemView(focused)
                    if (itemView != null) {
                        val pos = rvResults.getChildAdapterPosition(itemView)
                        if (pos != RecyclerView.NO_POSITION) {
                            if (currentTab == SearchTab.MESSAGES) {
                                val msgItem = messageSearchAdapter?.getItemAt(pos)
                                if (msgItem != null) {
                                    dismiss()
                                    onMessageSelected(msgItem.chatId, msgItem.messageId)
                                    return true
                                }
                            } else {
                                val chatItem = chatAdapter?.getItemAt(pos)
                                if (chatItem != null) {
                                    dismiss()
                                    onChatSelected(chatItem)
                                    return true
                                }
                            }
                        }
                    }
                } else if ((rvResults.adapter?.itemCount ?: 0) > 0) {
                    rvResults.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                    return true
                }
            }

            return super.onKeyDown(keyCode, event)
        }
    }
}
