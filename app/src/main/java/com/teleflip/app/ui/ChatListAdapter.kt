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
    private val onChatSelected: (ChatModel) -> Unit,
) : ListAdapter<ChatModel, ChatListAdapter.ChatViewHolder>(ChatDiffCallback()) {
    private var attachedRecyclerView: RecyclerView? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedRecyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        if (attachedRecyclerView == recyclerView) {
            attachedRecyclerView = null
        }
    }

    override fun submitList(
        newItems: List<ChatModel>?,
        commitCallback: Runnable?,
    ) {
        val rv = attachedRecyclerView
        var focusedChatId: Long? = null
        var oldPos = -1

        if (rv != null && rv.hasFocus()) {
            val focusedChild = rv.focusedChild
            if (focusedChild != null) {
                oldPos = rv.getChildAdapterPosition(focusedChild)
                if (oldPos in 0 until currentList.size) {
                    focusedChatId = getItem(oldPos)?.id
                }
            }
        }

        val itemsCopy = if (newItems != null) ArrayList(newItems) else null

        super.submitList(
            itemsCopy,
            Runnable {
                if (rv != null && focusedChatId != null) {
                    val list = currentList
                    var targetPos = list.indexOfFirst { it.id == focusedChatId }
                    if (targetPos == -1 && list.isNotEmpty() && oldPos != -1) {
                        targetPos = oldPos.coerceIn(0, list.size - 1)
                    }

                    if (targetPos != -1 && list.isNotEmpty()) {
                        rv.post {
                            rv.scrollToPosition(targetPos)
                            val vh = rv.findViewHolderForAdapterPosition(targetPos)
                            if (vh != null) {
                                vh.itemView.requestFocus()
                            } else {
                                val listener =
                                    object : RecyclerView.OnChildAttachStateChangeListener {
                                        override fun onChildViewAttachedToWindow(view: View) {
                                            if (rv.getChildAdapterPosition(view) == targetPos) {
                                                view.requestFocus()
                                                rv.removeOnChildAttachStateChangeListener(this)
                                            }
                                        }

                                        override fun onChildViewDetachedFromWindow(view: View) {}
                                    }
                                rv.addOnChildAttachStateChangeListener(listener)
                            }
                        }
                    }
                }
                commitCallback?.run()
            },
        )
    }

    override fun submitList(newItems: List<ChatModel>?) {
        submitList(newItems, null)
    }

    fun getItemAt(position: Int): ChatModel? {
        return if (position in 0 until currentList.size) getItem(position) else null
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

            if (chat.unreadCount > 0) {
                tvUnread.visibility = View.VISIBLE
                tvUnread.text = chat.unreadCount.toString()
            } else {
                tvUnread.visibility = View.GONE
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
