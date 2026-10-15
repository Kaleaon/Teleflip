package com.teleflip.app.ui

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import com.teleflip.app.R
import com.teleflip.app.model.MediaType
import com.teleflip.app.model.MessageModel
import com.teleflip.app.model.MessageStatus
import java.util.Locale

class MessageAdapter(
    private val onMessageFocused: ((MessageModel) -> Unit)? = null,
    private val onMessageAction: ((MessageModel) -> Unit)? = null
) : ListAdapter<MessageModel, MessageAdapter.MessageViewHolder>(MessageDiffCallback()) {

    private var playingMessageId: Long? = null
    private var currentSpeed: Float = 1.0f

    override fun submitList(newItems: List<MessageModel>?) {
        super.submitList(if (newItems != null) ArrayList(newItems) else null)
    }

    fun setPlayingMessageId(id: Long?, speed: Float = currentSpeed) {
        playingMessageId = id
        currentSpeed = speed
        if (currentList.isNotEmpty()) {
            notifyItemRangeChanged(0, currentList.size)
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        currentSpeed = speed
        if (currentList.isNotEmpty()) {
            notifyItemRangeChanged(0, currentList.size)
        }
    }

    fun getItemAt(position: Int): MessageModel? {
        return if (position in 0 until currentList.size) getItem(position) else null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.view_message_item, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: MessageViewHolder) {
        super.onViewRecycled(holder)
        holder.clearImage()
    }

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
                    val speedStr = String.format(Locale.US, "%.1fx", currentSpeed)
                    val icon = if (isPlaying) "⏸ Playing ($speedStr)" else "▶ Play (${message.durationSeconds}s)"
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

            // Photo image display with accessibility contentDescription
            ivPhoto.contentDescription = ContentDescriptionFormatter.getPhotoDescription(context, message.mediaCaption)
            if (message.mediaType == MediaType.PHOTO) {
                ivPhoto.loadBitmapFromFile(message.mediaFilePath)
            } else {
                ivPhoto.setImageDrawable(null)
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

        fun clearImage() {
            ivPhoto.setImageDrawable(null)
        }
    }

    class MessageDiffCallback : DiffUtil.ItemCallback<MessageModel>() {
        override fun areItemsTheSame(oldItem: MessageModel, newItem: MessageModel): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: MessageModel, newItem: MessageModel): Boolean {
            return oldItem == newItem
        }
    }
}
