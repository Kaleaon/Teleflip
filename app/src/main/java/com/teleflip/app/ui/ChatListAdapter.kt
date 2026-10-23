package com.teleflip.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.teleflip.app.R
import com.teleflip.app.model.ChatModel

class ChatListAdapter(
    private val onChatFocused: ((ChatModel) -> Unit)? = null,
    private val onChatSelected: (ChatModel) -> Unit,
) : ListAdapter<ChatModel, ChatListAdapter.ChatViewHolder>(ChatDiffCallback()) {
    override fun submitList(newItems: List<ChatModel>?) {
        super.submitList(if (newItems != null) ArrayList(newItems) else null)
    }

    override fun submitList(
        newItems: List<ChatModel>?,
        commitCallback: Runnable?,
    ) {
        super.submitList(if (newItems != null) ArrayList(newItems) else null, commitCallback)
    }

    fun getItemAt(position: Int): ChatModel? {
        return if (position in 0 until currentList.size) getItem(position) else null
    }

    fun getPositionForChatId(chatId: Long): Int {
        return currentList.indexOfFirst { it.id == chatId }
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ChatViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.view_chat_item, parent, false)
        return ChatViewHolder(view)
    }

    override fun onBindViewHolder(
        holder: ChatViewHolder,
        position: Int,
    ) {
        holder.bind(getItem(position), position)
    }

    override fun onViewRecycled(holder: ChatViewHolder) {
        super.onViewRecycled(holder)
        holder.clearImage()
    }

    inner class ChatViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvShortcut: TextView = itemView.findViewById(R.id.tv_shortcut_num)
        private val ivAvatar: ImageView = itemView.findViewById(R.id.iv_avatar)
        private val tvAvatar: TextView = itemView.findViewById(R.id.tv_avatar)
        private val tvTitle: TextView = itemView.findViewById(R.id.tv_chat_title)
        private val tvStarBadge: TextView = itemView.findViewById(R.id.tv_star_badge)
        private val tvMuteBadge: TextView? = itemView.findViewById(R.id.tv_mute_badge)
        private val tvPinBadge: TextView? = itemView.findViewById(R.id.tv_pin_badge)
        private val tvTime: TextView = itemView.findViewById(R.id.tv_chat_time)
        private val tvLastMsg: TextView = itemView.findViewById(R.id.tv_last_message)
        private val tvUnread: TextView = itemView.findViewById(R.id.tv_unread_count)

        fun bind(
            chat: ChatModel,
            position: Int,
        ) {
            val context = itemView.context
            ivAvatar.contentDescription = ContentDescriptionFormatter.getAvatarDescription(context, chat.title)

            // Keypad shortcut 1-9
            if (position < 9) {
                tvShortcut.visibility = View.VISIBLE
                tvShortcut.text = (position + 1).toString()
            } else {
                tvShortcut.visibility = View.INVISIBLE
            }

            // Render photo avatar or fallback to colored letter initials
            ivAvatar.loadCircularAvatarOrFallback(chat.avatarPath, chat.avatarLetters, tvAvatar)

            val titlePrefix =
                buildString {
                    if (chat.isSecretChat) append("🔒 ")
                    if (chat.isForum) append("🧵 ")
                }
            tvTitle.text = "$titlePrefix${chat.title}"
            tvStarBadge.visibility = if (chat.isPremium) View.VISIBLE else View.GONE
            tvMuteBadge?.visibility = if (chat.isMuted) View.VISIBLE else View.GONE

            val showPinOrFolder = chat.isPinned || chat.folderName.isNotBlank() || chat.isArchived
            tvPinBadge?.visibility = if (showPinOrFolder) View.VISIBLE else View.GONE
            tvPinBadge?.text =
                when {
                    chat.isPinned -> "📌"
                    chat.isArchived -> "📦"
                    chat.folderName.isNotBlank() -> "📁"
                    else -> ""
                }

            tvTime.text = chat.time

            if (chat.draftText.isNotBlank()) {
                tvLastMsg.text = "Draft: ${chat.draftText}"
                tvLastMsg.setTextColor(ContextCompat.getColor(context, R.color.focus_highlight_gold))
            } else {
                tvLastMsg.text = chat.lastMessage
                tvLastMsg.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            }

            if (chat.unreadCount > 0 || chat.isMarkedUnread) {
                tvUnread.visibility = View.VISIBLE
                tvUnread.text = if (chat.unreadCount > 0) chat.unreadCount.toString() else "•"
            } else {
                tvUnread.visibility = View.GONE
            }

            itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    onChatFocused?.invoke(chat)
                }
            }

            itemView.setOnClickListener {
                onChatSelected(chat)
            }
        }

        fun clearImage() {
            ivAvatar.setImageDrawable(null)
        }
    }

    class ChatDiffCallback : DiffUtil.ItemCallback<ChatModel>() {
        override fun areItemsTheSame(
            oldItem: ChatModel,
            newItem: ChatModel,
        ): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(
            oldItem: ChatModel,
            newItem: ChatModel,
        ): Boolean {
            return oldItem == newItem
        }
    }
}
