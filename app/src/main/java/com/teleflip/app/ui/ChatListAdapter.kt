package com.teleflip.app.ui

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.recyclerview.widget.RecyclerView
import com.teleflip.app.R
import com.teleflip.app.model.ChatModel
import java.io.File

class ChatListAdapter(
    private val onChatSelected: (ChatModel) -> Unit
) : RecyclerView.Adapter<ChatListAdapter.ChatViewHolder>() {

    private val items = mutableListOf<ChatModel>()

    fun submitList(newItems: List<ChatModel>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun getItemAt(position: Int): ChatModel? {
        return if (position in 0 until items.size) items[position] else null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.view_chat_item, parent, false)
        return ChatViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        holder.bind(items[position], position)
    }

    override fun getItemCount(): Int = items.size

    inner class ChatViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvShortcut: TextView = itemView.findViewById(R.id.tv_shortcut_num)
        private val ivAvatar: ImageView = itemView.findViewById(R.id.iv_avatar)
        private val tvAvatar: TextView = itemView.findViewById(R.id.tv_avatar)
        private val tvTitle: TextView = itemView.findViewById(R.id.tv_chat_title)
        private val tvTime: TextView = itemView.findViewById(R.id.tv_chat_time)
        private val tvLastMsg: TextView = itemView.findViewById(R.id.tv_last_message)
        private val tvUnread: TextView = itemView.findViewById(R.id.tv_unread_count)

        fun bind(chat: ChatModel, position: Int) {
            ivAvatar.contentDescription = ContentDescriptionFormatter.getAvatarDescription(itemView.context, chat.title)

            // Keypad shortcut 1-9
            if (position < 9) {
                tvShortcut.visibility = View.VISIBLE
                tvShortcut.text = (position + 1).toString()
            } else {
                tvShortcut.visibility = View.INVISIBLE
            }

            // Render photo avatar or fallback to colored letter initials
            if (!chat.avatarPath.isNullOrEmpty() && File(chat.avatarPath).exists()) {
                try {
                    val bitmap = BitmapFactory.decodeFile(chat.avatarPath)
                    if (bitmap != null) {
                        val circularDrawable = RoundedBitmapDrawableFactory.create(itemView.resources, bitmap).apply {
                            isCircular = true
                        }
                        ivAvatar.setImageDrawable(circularDrawable)
                        ivAvatar.visibility = View.VISIBLE
                        tvAvatar.visibility = View.GONE
                    } else {
                        showLetterFallback(chat)
                    }
                } catch (e: Exception) {
                    showLetterFallback(chat)
                }
            } else {
                showLetterFallback(chat)
            }

            tvTitle.text = chat.title
            tvTime.text = chat.time
            tvLastMsg.text = chat.lastMessage

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

        private fun showLetterFallback(chat: ChatModel) {
            ivAvatar.visibility = View.GONE
            tvAvatar.visibility = View.VISIBLE
            tvAvatar.text = chat.avatarLetters
        }
    }
}
