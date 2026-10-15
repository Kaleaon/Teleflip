package com.teleflip.app.ui

import android.graphics.BitmapFactory
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.teleflip.app.R
import com.teleflip.app.model.MediaType
import com.teleflip.app.model.MessageModel
import com.teleflip.app.model.MessageStatus
import java.io.File

class MessageAdapter(
    private val onMessageFocused: ((MessageModel) -> Unit)? = null,
    private val onMessageAction: ((MessageModel) -> Unit)? = null
) : RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    private val items = mutableListOf<MessageModel>()
    private var playingMessageId: Long? = null

    fun submitList(newItems: List<MessageModel>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun setPlayingMessageId(id: Long?) {
        playingMessageId = id
        notifyDataSetChanged()
    }

    fun getPositionForMessageId(messageId: Long): Int {
        return items.indexOfFirst { it.id == messageId }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.view_message_item, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val rootLayout: LinearLayout = itemView.findViewById(R.id.layout_message_root)
        private val bubbleLayout: LinearLayout = itemView.findViewById(R.id.layout_bubble)
        private val tvSender: TextView = itemView.findViewById(R.id.tv_sender_name)
        private val tvMedia: TextView = itemView.findViewById(R.id.tv_media_indicator)
        private val ivPhoto: ImageView = itemView.findViewById(R.id.iv_message_photo)
        private val tvText: TextView = itemView.findViewById(R.id.tv_message_text)
        private val tvTime: TextView = itemView.findViewById(R.id.tv_message_time)
        private val tvStatus: TextView = itemView.findViewById(R.id.tv_read_status)

        fun bind(message: MessageModel) {
            val context = itemView.context

            if (message.isOutbound) {
                rootLayout.gravity = Gravity.END
                bubbleLayout.background = ContextCompat.getDrawable(context, R.drawable.bg_outbound_bubble)
                tvSender.visibility = View.GONE
                tvStatus.visibility = View.VISIBLE
                tvStatus.text = when (message.status) {
                    MessageStatus.SENDING -> " ⏱"
                    MessageStatus.SENT -> " ✓"
                    MessageStatus.READ -> " ✓✓"
                    MessageStatus.FAILED -> " ⚠"
                }
            } else {
                rootLayout.gravity = Gravity.START
                bubbleLayout.background = ContextCompat.getDrawable(context, R.drawable.bg_inbound_bubble)
                tvSender.visibility = if (message.senderName.isNotBlank() && message.senderName != "You") {
                    tvSender.text = message.senderName
                    View.VISIBLE
                } else {
                    View.GONE
                }
                tvStatus.visibility = View.GONE
            }

            // Media attachment preview
            when (message.mediaType) {
                MediaType.VOICE -> {
                    tvMedia.visibility = View.VISIBLE
                    val isPlaying = (playingMessageId == message.id)
                    val icon = if (isPlaying) "⏸ Playing..." else "▶ Play (${message.durationSeconds}s)"
                    tvMedia.text = "🎤 [Voice Message] $icon"
                }
                MediaType.LOCATION -> {
                    tvMedia.visibility = View.VISIBLE
                    tvMedia.text = "📍 [Location: ${String.format("%.4f, %.4f", message.latitude, message.longitude)}]"
                }
                MediaType.PHOTO -> {
                    tvMedia.visibility = View.VISIBLE
                    tvMedia.text = if (message.mediaCaption.isNotBlank()) "📷 ${message.mediaCaption}" else "📷 [Photo]"
                }
                MediaType.DOCUMENT -> {
                    tvMedia.visibility = View.VISIBLE
                    tvMedia.text = "📄 [File] ${message.mediaCaption}"
                }
                MediaType.STICKER -> {
                    tvMedia.visibility = View.VISIBLE
                    tvMedia.text = "🎨 [Sticker]"
                }
                MediaType.VIDEO -> {
                    tvMedia.visibility = View.VISIBLE
                    tvMedia.text = "📹 [Video]"
                }
                MediaType.NONE -> {
                    tvMedia.visibility = View.GONE
                }
            }

            // Photo image display if downloaded
            if (message.mediaType == MediaType.PHOTO && !message.mediaFilePath.isNullOrEmpty() && File(message.mediaFilePath).exists()) {
                try {
                    val bmp = BitmapFactory.decodeFile(message.mediaFilePath)
                    if (bmp != null) {
                        ivPhoto.setImageBitmap(bmp)
                        ivPhoto.visibility = View.VISIBLE
                    } else {
                        ivPhoto.visibility = View.GONE
                    }
                } catch (e: Exception) {
                    ivPhoto.visibility = View.GONE
                }
            } else {
                ivPhoto.visibility = View.GONE
            }

            tvText.text = message.text
            tvText.visibility = if (message.text.isNotBlank()) View.VISIBLE else View.GONE
            tvTime.text = message.time

            itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    onMessageFocused?.invoke(message)
                }
            }

            itemView.setOnClickListener {
                onMessageAction?.invoke(message)
            }
        }
    }
}
