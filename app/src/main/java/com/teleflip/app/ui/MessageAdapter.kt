package com.teleflip.app.ui

import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ImageSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.teleflip.app.R
import com.teleflip.app.model.MediaType
import com.teleflip.app.model.MessageModel
import com.teleflip.app.model.MessageStatus
import com.teleflip.app.telegram.TelegramManager
import java.io.File
import java.util.Locale

class MessageAdapter(
    private val onMessageFocused: ((MessageModel) -> Unit)? = null,
    private val onMessageAction: ((MessageModel) -> Unit)? = null,
    private val onTranscribeClick: ((MessageModel) -> Unit)? = null,
    private val customEmojiResolver: ((String) -> String?)? = null,
) : ListAdapter<MessageModel, MessageAdapter.MessageViewHolder>(MessageDiffCallback()) {
    companion object {
        const val PAYLOAD_PLAYBACK_STATE = "PAYLOAD_PLAYBACK_STATE"
    }

    private var playingMessageId: Long? = null
    private var currentSpeed: Float = 1.0f

    override fun submitList(newItems: List<MessageModel>?) {
        super.submitList(if (newItems != null) ArrayList(newItems) else null)
    }

    fun setPlayingMessageId(
        id: Long?,
        speed: Float = currentSpeed,
    ) {
        val previousId = playingMessageId
        playingMessageId = id
        currentSpeed = speed

        val previousPos = previousId?.let { getPositionForMessageId(it) } ?: -1
        val currentPos = id?.let { getPositionForMessageId(it) } ?: -1

        if (previousPos != -1) {
            notifyItemChanged(previousPos, PAYLOAD_PLAYBACK_STATE)
        }
        if (currentPos != -1 && currentPos != previousPos) {
            notifyItemChanged(currentPos, PAYLOAD_PLAYBACK_STATE)
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        currentSpeed = speed
        val activeId = playingMessageId ?: return
        val activePos = getPositionForMessageId(activeId)
        if (activePos != -1) {
            notifyItemChanged(activePos, PAYLOAD_PLAYBACK_STATE)
        }
    }

    fun getItemAt(position: Int): MessageModel? {
        return if (position in 0 until currentList.size) getItem(position) else null
    }

    fun getPositionForMessageId(messageId: Long): Int {
        return currentList.indexOfFirst { it.id == messageId }
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): MessageViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.view_message_item, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(
        holder: MessageViewHolder,
        position: Int,
    ) {
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(
        holder: MessageViewHolder,
        position: Int,
        payloads: MutableList<Any>,
    ) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
        } else {
            var handled = false
            for (payload in payloads) {
                if (payload == PAYLOAD_PLAYBACK_STATE) {
                    holder.bindPlaybackState(getItem(position))
                    handled = true
                }
            }
            if (!handled) {
                super.onBindViewHolder(holder, position, payloads)
            }
        }
    }

    override fun onViewRecycled(holder: MessageViewHolder) {
        super.onViewRecycled(holder)
        holder.clearImage()
    }

    inner class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val rootLayout: LinearLayout = itemView.findViewById(R.id.layout_message_root)
        private val bubbleLayout: LinearLayout = itemView.findViewById(R.id.layout_bubble)
        private val tvSender: TextView = itemView.findViewById(R.id.tv_sender_name)
        private val tvForwarded: TextView? = itemView.findViewById(R.id.tv_forwarded_header)
        private val tvReplyQuote: TextView? = itemView.findViewById(R.id.tv_reply_quote)
        private val tvMedia: TextView = itemView.findViewById(R.id.tv_media_indicator)
        private val ivPhoto: ImageView = itemView.findViewById(R.id.iv_message_photo)
        private val tvStarBadge: TextView? = itemView.findViewById(R.id.tv_star_badge)
        private val tvText: TextView = itemView.findViewById(R.id.tv_message_text)
        private val btnTranscribe: TextView? = itemView.findViewById(R.id.btn_transcribe)
        private val tvTranscript: TextView? = itemView.findViewById(R.id.tv_transcript)
        private val tvReactions: TextView? = itemView.findViewById(R.id.tv_reactions)
        private val tvTime: TextView = itemView.findViewById(R.id.tv_message_time)
        private val tvStatus: TextView = itemView.findViewById(R.id.tv_read_status)

        fun bindPlaybackState(message: MessageModel) {
            if (message.mediaType == MediaType.VOICE) {
                tvMedia.visibility = View.VISIBLE
                val isPlaying = (playingMessageId == message.id)
                val speedStr = String.format(Locale.US, "%.1fx", currentSpeed)
                val icon = if (isPlaying) "⏸ Playing ($speedStr)" else "▶ Play (${message.durationSeconds}s)"
                tvMedia.text = "🎤 [Voice Message] $icon"
            }
        }

        fun bind(message: MessageModel) {
            val context = itemView.context

            if (message.isOutbound) {
                rootLayout.gravity = Gravity.END
                bubbleLayout.background = ContextCompat.getDrawable(context, R.drawable.bg_outbound_bubble)
                tvSender.visibility = View.GONE
                tvStatus.visibility = View.VISIBLE
                tvStatus.text =
                    when (message.status) {
                        MessageStatus.SENDING -> " ⏱"
                        MessageStatus.SENT -> " ✓"
                        MessageStatus.READ -> " ✓✓"
                        MessageStatus.FAILED -> " ⚠"
                    }
            } else {
                rootLayout.gravity = Gravity.START
                bubbleLayout.background = ContextCompat.getDrawable(context, R.drawable.bg_inbound_bubble)
                tvSender.visibility =
                    if (message.senderName.isNotBlank() && message.senderName != "You") {
                        tvSender.text = message.senderName
                        View.VISIBLE
                    } else {
                        View.GONE
                    }
                tvStatus.visibility = View.GONE
            }

            if (message.forwardedFrom.isNotBlank()) {
                tvForwarded?.visibility = View.VISIBLE
                tvForwarded?.text = "↪ Forwarded from ${message.forwardedFrom}"
            } else {
                tvForwarded?.visibility = View.GONE
            }

            if (message.replyToPreview.isNotBlank()) {
                tvReplyQuote?.visibility = View.VISIBLE
                tvReplyQuote?.text = "↩ ${message.replyToPreview}"
            } else if (message.replyToMessageId != 0L) {
                tvReplyQuote?.visibility = View.VISIBLE
                tvReplyQuote?.text = "↩ Reply to #${message.replyToMessageId}"
            } else {
                tvReplyQuote?.visibility = View.GONE
            }

            // Media attachment preview
            when (message.mediaType) {
                MediaType.VOICE -> {
                    tvMedia.visibility = View.VISIBLE
                    val isPlaying = (playingMessageId == message.id)
                    val speedStr = String.format(Locale.US, "%.1fx", currentSpeed)
                    val icon = if (isPlaying) "⏸ Playing ($speedStr)" else "▶ Play (${message.durationSeconds}s)"
                    tvMedia.text = "🎤 [Voice Message] $icon"

                    val stateDesc =
                        ContentDescriptionFormatter.getVoiceStateDescription(
                            context,
                            isPlaying,
                            speedStr,
                            message.durationSeconds,
                        )
                    ViewCompat.setStateDescription(tvMedia, stateDesc)
                    tvMedia.contentDescription =
                        ContentDescriptionFormatter.getVoiceMessageDescription(
                            context, isPlaying, message.durationSeconds,
                        )
                }
                MediaType.LOCATION -> {
                    ViewCompat.setStateDescription(tvMedia, null)
                    tvMedia.contentDescription = null
                    tvMedia.visibility = View.VISIBLE
                    tvMedia.text = "📍 [Location: ${String.format("%.4f, %.4f", message.latitude, message.longitude)}]"
                }
                MediaType.PHOTO -> {
                    ViewCompat.setStateDescription(tvMedia, null)
                    tvMedia.contentDescription = null
                    tvMedia.visibility = View.VISIBLE
                    tvMedia.text = if (message.mediaCaption.isNotBlank()) "📷 ${message.mediaCaption}" else "📷 [Photo • OK to Zoom]"
                }
                MediaType.DOCUMENT -> {
                    ViewCompat.setStateDescription(tvMedia, null)
                    tvMedia.contentDescription = null
                    tvMedia.visibility = View.VISIBLE
                    tvMedia.text = if (message.mediaCaption.isNotBlank()) "📄 [File] ${message.mediaCaption}" else "📄 [File]"
                }
                MediaType.STICKER -> {
                    ViewCompat.setStateDescription(tvMedia, null)
                    tvMedia.contentDescription = null
                    tvMedia.visibility = View.VISIBLE
                    val emoji = message.stickerEmoji.ifBlank { message.mediaCaption }
                    tvMedia.text = if (emoji.isNotBlank()) "🎨 [Sticker $emoji]" else "🎨 [Sticker]"
                }
                MediaType.VIDEO -> {
                    ViewCompat.setStateDescription(tvMedia, null)
                    tvMedia.contentDescription = null
                    tvMedia.visibility = View.VISIBLE
                    val dur = if (message.durationSeconds > 0) " (${message.durationSeconds}s)" else ""
                    tvMedia.text = if (message.mediaCaption.isNotBlank()) "📹 [Video$dur] ${message.mediaCaption}" else "📹 [Video$dur] ▶ Press OK to Play"
                }
                MediaType.ANIMATION -> {
                    ViewCompat.setStateDescription(tvMedia, null)
                    tvMedia.contentDescription = null
                    tvMedia.visibility = View.VISIBLE
                    tvMedia.text = if (message.mediaCaption.isNotBlank()) "🎞️ [GIF] ${message.mediaCaption}" else "🎞️ [GIF] ▶ Press OK to Play"
                }
                MediaType.AUDIO -> {
                    ViewCompat.setStateDescription(tvMedia, null)
                    tvMedia.contentDescription = null
                    tvMedia.visibility = View.VISIBLE
                    tvMedia.text = if (message.mediaCaption.isNotBlank()) "🎵 [Audio] ${message.mediaCaption}" else "🎵 [Audio]"
                }
                MediaType.POLL -> {
                    ViewCompat.setStateDescription(tvMedia, null)
                    tvMedia.contentDescription = null
                    tvMedia.visibility = View.VISIBLE
                    val statusTag =
                        if (message.pollIsClosed) {
                            "Closed"
                        } else if (message.pollIsAnonymous) {
                            "Anonymous Poll"
                        } else {
                            "Public Poll"
                        }
                    tvMedia.text = "📊 [$statusTag • Press OK to Vote]"
                }
                MediaType.PAID_MEDIA -> {
                    tvMedia.visibility = View.VISIBLE
                    if (message.isPaidMediaLocked) {
                        val cap = if (message.mediaCaption.isNotBlank()) " • ${message.mediaCaption}" else ""
                        tvMedia.text = "🔒 [Paid Media • ⭐ ${message.starCount} Stars$cap • Press OK to Unlock]"
                    } else {
                        tvMedia.text = if (message.mediaCaption.isNotBlank()) "🔓 ${message.mediaCaption}" else "🔓 [Unlocked Media • Press OK to View]"
                    }
                }
                MediaType.NONE -> {
                    ViewCompat.setStateDescription(tvMedia, null)
                    tvMedia.contentDescription = null
                    tvMedia.visibility = View.GONE
                }
            }

            // Photo, Sticker, GIF, and Paid Media display with accessibility contentDescription
            val isVisualMedia =
                message.mediaType == MediaType.PHOTO ||
                    message.mediaType == MediaType.STICKER ||
                    message.mediaType == MediaType.ANIMATION ||
                    message.mediaType == MediaType.PAID_MEDIA
            if (isVisualMedia) {
                ivPhoto.contentDescription =
                    when (message.mediaType) {
                        MediaType.STICKER -> {
                            val emo = message.stickerEmoji.ifBlank { "Sticker" }
                            "Sticker $emo"
                        }
                        MediaType.ANIMATION -> "GIF Animation"
                        MediaType.PAID_MEDIA -> {
                            if (message.isPaidMediaLocked) {
                                "Paid Media post, ${message.starCount} Stars"
                            } else {
                                "Unlocked Paid Media"
                            }
                        }
                        else -> ContentDescriptionFormatter.getPhotoDescription(context, message.mediaCaption)
                    }
                val density = context.resources.displayMetrics.density
                val lp = ivPhoto.layoutParams
                if (message.mediaType == MediaType.STICKER) {
                    ivPhoto.scaleType = ImageView.ScaleType.FIT_CENTER
                    lp.width = (120 * density).toInt()
                    lp.height = (120 * density).toInt()
                } else {
                    ivPhoto.scaleType = ImageView.ScaleType.CENTER_CROP
                    lp.width = (160 * density).toInt()
                    lp.height = (120 * density).toInt()
                }
                ivPhoto.layoutParams = lp

                if (!message.mediaFilePath.isNullOrEmpty()) {
                    ivPhoto.loadMediaFromFile(message.mediaFilePath)
                } else if (message.mediaType == MediaType.PAID_MEDIA && message.isPaidMediaLocked) {
                    ivPhoto.setImageResource(R.drawable.bg_inbound_bubble)
                    ivPhoto.visibility = View.VISIBLE
                } else {
                    ivPhoto.setImageDrawable(null)
                    ivPhoto.visibility = View.GONE
                }

                if (message.mediaType == MediaType.PAID_MEDIA && message.isPaidMediaLocked) {
                    tvStarBadge?.visibility = View.VISIBLE
                    tvStarBadge?.text = "⭐ ${message.starCount} Stars"
                } else {
                    tvStarBadge?.visibility = View.GONE
                }
            } else {
                ivPhoto.setImageDrawable(null)
                ivPhoto.visibility = View.GONE
                tvStarBadge?.visibility = View.GONE
            }

            if (message.mediaType == MediaType.POLL && message.pollOptions.isNotEmpty()) {
                val pollBody =
                    buildString {
                        appendLine(message.pollQuestion.ifBlank { message.text })
                        message.pollOptions.forEachIndexed { idx, opt ->
                            val mark = if (opt.isChosen) "[✓]" else "[ ]"
                            appendLine("${idx + 1}. $mark ${opt.text} (${opt.votePercentage}% • ${opt.voterCount})")
                        }
                        append("Total votes: ${message.pollTotalVoters}")
                    }
                tvText.text = pollBody
                tvText.visibility = View.VISIBLE
            } else {
                tvText.text = message.text
                tvText.visibility = if (message.text.isNotBlank()) View.VISIBLE else View.GONE
            }

            if (message.mediaType == MediaType.VOICE) {
                btnTranscribe?.visibility = View.VISIBLE
                btnTranscribe?.setOnClickListener {
                    onTranscribeClick?.invoke(message)
                }
                if (message.transcript.isNotBlank()) {
                    tvTranscript?.visibility = View.VISIBLE
                    tvTranscript?.text = "💬 ${message.transcript}"
                } else {
                    tvTranscript?.visibility = View.GONE
                }
            } else {
                btnTranscribe?.visibility = View.GONE
                tvTranscript?.visibility = View.GONE
            }

            if (message.reactions.isNotEmpty()) {
                tvReactions?.visibility = View.VISIBLE
                val ssb = SpannableStringBuilder()
                val density = context.resources.displayMetrics.density
                val targetSize = (16 * density).toInt().coerceAtLeast(1)

                message.reactions.forEachIndexed { idx, reaction ->
                    if (idx > 0) ssb.append(" ")

                    if (reaction.startsWith("custom_emoji_")) {
                        val filePath =
                            customEmojiResolver?.invoke(reaction)
                                ?: TelegramManager.getCustomEmojiFilePath(reaction)
                        var drawableAdded = false
                        if (!filePath.isNullOrBlank()) {
                            try {
                                val file = File(filePath)
                                if (file.exists()) {
                                    val bitmap = BitmapFactory.decodeFile(filePath)
                                    if (bitmap != null) {
                                        val drawable = BitmapDrawable(context.resources, bitmap)
                                        drawable.setBounds(0, 0, targetSize, targetSize)
                                        val startPos = ssb.length
                                        ssb.append(" ")
                                        ssb.setSpan(
                                            ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM),
                                            startPos,
                                            startPos + 1,
                                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                                        )
                                        drawableAdded = true
                                    }
                                }
                            } catch (_: Throwable) {
                            }
                        }
                        if (!drawableAdded) {
                            val sticker = TelegramManager.getCustomEmojiSticker(reaction)
                            val fallbackSymbol = sticker?.emoji?.ifBlank { "✨" } ?: "✨"
                            ssb.append(fallbackSymbol)
                        }
                    } else {
                        ssb.append(reaction)
                    }
                }
                tvReactions?.text = ssb
            } else {
                tvReactions?.visibility = View.GONE
            }

            val metaPrefix =
                buildString {
                    if (message.selfDestructSeconds > 0) append("⏱️${message.selfDestructSeconds}s ")
                    if (message.isPinned) append("📌 ")
                    if (message.isEdited) append("edited ")
                }
            tvTime.text = "$metaPrefix${message.time}"

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
        override fun areItemsTheSame(
            oldItem: MessageModel,
            newItem: MessageModel,
        ): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(
            oldItem: MessageModel,
            newItem: MessageModel,
        ): Boolean {
            return oldItem == newItem
        }
    }
}
