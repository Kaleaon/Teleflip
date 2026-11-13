package com.teleflip.app.ui

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.teleflip.app.R
import com.teleflip.app.model.ChatModel
import com.teleflip.app.model.SavedGifItem
import com.teleflip.app.model.StickerItem
import com.teleflip.app.telegram.TelegramManager
import java.io.File

data class PickerItem(
    val id: String,
    val title: String,
    val emoji: String = "",
    val filePath: String? = null,
    val fileId: Int = 0,
    val isGif: Boolean = false,
    val rawSticker: StickerItem? = null,
    val rawGif: SavedGifItem? = null,
)

data class PickerPack(
    val title: String,
    val isGif: Boolean,
    val items: List<PickerItem>,
)

class GifStickerPickerDialog(
    context: Context,
    private val chat: ChatModel,
    private val telegramManager: TelegramManager,
    private val initialIsGif: Boolean = true,
    private val onSendSticker: ((StickerItem) -> Unit)? = null,
    private val onSendGif: ((SavedGifItem) -> Unit)? = null,
) : BaseDialog(context) {
    internal val packs = mutableListOf<PickerPack>()
    internal var currentPackIndex: Int = 0
        private set

    internal val packCount: Int
        get() = packs.size

    internal val currentPack: PickerPack?
        get() = packs.getOrNull(currentPackIndex)

    private var softkeyManager: SoftkeyManager? = null
    private var adapter: PickerGridAdapter? = null

    companion object {
        private const val GRID_COLUMNS = 3

        private val REACTION_GIFS =
            listOf(
                "🎉 Party / Celebration",
                "👋 Hello / Wave",
                "👏 Applause / Bravo",
                "🤣 Laughing Out Loud",
                "👍 Thumbs Up",
                "🔥 Fire / Awesome",
                "💃 Dance / Groove",
                "🤝 Deal / Handshake",
            )

        private val POPULAR_STICKERS =
            listOf(
                "👍" to "Thumbs Up",
                "❤️" to "Red Heart",
                "😂" to "Laughing",
                "🔥" to "Fire",
                "🎉" to "Celebrate",
                "🙏" to "Thank You",
                "😎" to "Cool",
                "🚀" to "Rocket",
                "👏" to "Applause",
                "💯" to "100",
                "🤔" to "Thinking",
                "😢" to "Crying",
            )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_gif_sticker_picker)

        window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )

        buildInitialPacks()

        val softkeyBar = findViewById<View>(R.id.softkey_bar)
        val tvLeft = findViewById<TextView>(R.id.tv_softkey_left)
        val tvCenter = findViewById<TextView>(R.id.tv_softkey_center)
        val tvRight = findViewById<TextView>(R.id.tv_softkey_right)

        if (tvLeft != null && tvCenter != null && tvRight != null) {
            softkeyManager =
                SoftkeyManager(softkeyBar, tvLeft, tvCenter, tvRight).apply {
                    update(
                        left = "Prev",
                        center = "Send",
                        right = "Next",
                        onLeft = { prevPack() },
                        onCenter = { sendFocusedItem() },
                        onRight = { nextPack() },
                    )
                }
        }

        val rv = findViewById<RecyclerView>(R.id.rv_picker_grid)
        rv?.layoutManager = GridLayoutManager(context, GRID_COLUMNS)
        adapter =
            PickerGridAdapter(emptyList()) { item ->
                sendItem(item)
            }
        rv?.adapter = adapter

        updatePackDisplay()
        loadRemotePacks()
    }

    private fun buildInitialPacks() {
        packs.clear()

        // 1. Saved GIFs Pack
        val localGifs = telegramManager.getLocalSavedGifs()
        val savedGifItems =
            localGifs.mapIndexed { idx, gif ->
                PickerItem(
                    id = "saved_gif_${gif.fileId}_$idx",
                    title = gif.caption.ifBlank { "GIF #${idx + 1}" },
                    filePath = gif.filePath,
                    fileId = gif.fileId,
                    isGif = true,
                    rawGif = gif,
                )
            }
        packs.add(PickerPack("Saved GIFs", isGif = true, items = savedGifItems))

        // 2. Popular Reaction GIFs Pack
        val reactionItems =
            REACTION_GIFS.mapIndexed { idx, title ->
                PickerItem(
                    id = "reaction_gif_$idx",
                    title = title,
                    isGif = true,
                )
            }
        packs.add(PickerPack("Reaction GIFs", isGif = true, items = reactionItems))

        // 3. Saved Stickers Pack
        val localStickers = telegramManager.getLocalSavedStickers()
        val savedStickerItems =
            localStickers.mapIndexed { idx, st ->
                PickerItem(
                    id = "saved_sticker_${st.fileId}_$idx",
                    title = if (st.emoji.isNotBlank()) "${st.emoji} Sticker" else "Sticker #${idx + 1}",
                    emoji = st.emoji,
                    filePath = st.filePath,
                    fileId = st.fileId,
                    isGif = false,
                    rawSticker = st,
                )
            }
        packs.add(PickerPack("Saved Stickers", isGif = false, items = savedStickerItems))

        // 4. Popular Emoji Stickers Pack
        val popularStickerItems =
            POPULAR_STICKERS.mapIndexed { idx, (emoji, name) ->
                PickerItem(
                    id = "popular_sticker_$idx",
                    title = "$emoji $name",
                    emoji = emoji,
                    isGif = false,
                )
            }
        packs.add(PickerPack("Popular Stickers", isGif = false, items = popularStickerItems))

        // Select initial pack index based on whether opened as GIF or Sticker
        currentPackIndex =
            if (initialIsGif) {
                0
            } else {
                val stickerIndex = packs.indexOfFirst { !it.isGif }
                if (stickerIndex >= 0) stickerIndex else 0
            }
    }

    private fun loadRemotePacks() {
        telegramManager.loadSavedGifs { gifs ->
            if (gifs.isNotEmpty()) {
                val packIdx = packs.indexOfFirst { it.title == "Saved GIFs" }
                val items =
                    gifs.mapIndexed { idx, gif ->
                        PickerItem(
                            id = "saved_gif_${gif.fileId}_$idx",
                            title = gif.caption.ifBlank { "GIF #${idx + 1}" },
                            filePath = gif.filePath,
                            fileId = gif.fileId,
                            isGif = true,
                            rawGif = gif,
                        )
                    }
                if (packIdx >= 0) {
                    packs[packIdx] = PickerPack("Saved GIFs", isGif = true, items = items)
                    if (currentPackIndex == packIdx) {
                        updatePackDisplay()
                    }
                }
            }
        }

        telegramManager.loadSavedStickers { stickers ->
            if (stickers.isNotEmpty()) {
                val packIdx = packs.indexOfFirst { it.title == "Saved Stickers" }
                val items =
                    stickers.mapIndexed { idx, st ->
                        PickerItem(
                            id = "saved_st_${st.fileId}_$idx",
                            title = if (st.emoji.isNotBlank()) "${st.emoji} Sticker" else "Sticker #${idx + 1}",
                            emoji = st.emoji,
                            filePath = st.filePath,
                            fileId = st.fileId,
                            isGif = false,
                            rawSticker = st,
                        )
                    }
                if (packIdx >= 0) {
                    packs[packIdx] = PickerPack("Saved Stickers", isGif = false, items = items)
                    if (currentPackIndex == packIdx) {
                        updatePackDisplay()
                    }
                }
            }
        }

        telegramManager.loadInstalledStickers { sets ->
            if (sets.isNotEmpty()) {
                val installedPacks =
                    sets.map { set ->
                        val items =
                            set.stickers.mapIndexed { idx, st ->
                                PickerItem(
                                    id = "set_${set.id}_${st.fileId}_$idx",
                                    title = if (st.emoji.isNotBlank()) "${st.emoji} ${st.setName}" else "Sticker #${idx + 1}",
                                    emoji = st.emoji,
                                    filePath = st.filePath,
                                    fileId = st.fileId,
                                    isGif = false,
                                    rawSticker = st,
                                )
                            }
                        PickerPack("📦 ${set.title}", isGif = false, items = items)
                    }
                // Insert installed packs before Popular Stickers
                val popularIdx = packs.indexOfLast { it.title == "Popular Stickers" }
                val insertAt = if (popularIdx >= 0) popularIdx else packs.size
                packs.addAll(insertAt, installedPacks)
                updatePackDisplay()
            }
        }
    }

    internal fun nextPack() {
        if (packs.isEmpty()) return
        currentPackIndex = (currentPackIndex + 1) % packs.size
        updatePackDisplay()
    }

    internal fun prevPack() {
        if (packs.isEmpty()) return
        currentPackIndex = (currentPackIndex - 1 + packs.size) % packs.size
        updatePackDisplay()
    }

    private fun updatePackDisplay() {
        val pack = currentPack ?: return
        val titleView = findViewById<TextView>(R.id.tv_picker_title)
        val packNameView = findViewById<TextView>(R.id.tv_picker_pack_name)
        val emptyView = findViewById<TextView>(R.id.tv_picker_empty)
        val rv = findViewById<RecyclerView>(R.id.rv_picker_grid)

        titleView?.text = "Pack ${currentPackIndex + 1}/${packs.size}"
        packNameView?.text = pack.title

        if (pack.items.isEmpty()) {
            emptyView?.visibility = View.VISIBLE
            emptyView?.text = "No ${if (pack.isGif) "GIFs" else "stickers"} saved yet"
            adapter?.submitItems(emptyList())
        } else {
            emptyView?.visibility = View.GONE
            adapter?.submitItems(pack.items)
            rv?.scrollToPosition(0)
            rv?.post {
                rv.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
            }
        }
    }

    internal fun sendFocusedItem() {
        val rv = findViewById<RecyclerView>(R.id.rv_picker_grid)
        val pack = currentPack ?: return
        if (pack.items.isEmpty()) return

        val focused = rv?.findFocus()
        var targetItem: PickerItem? = null
        if (rv != null && focused != null) {
            val vh = rv.findContainingViewHolder(focused)
            val pos = vh?.bindingAdapterPosition ?: RecyclerView.NO_POSITION
            if (pos in pack.items.indices) {
                targetItem = pack.items[pos]
            }
        }
        if (targetItem == null) {
            targetItem = pack.items.firstOrNull()
        }

        targetItem?.let { sendItem(it) }
    }

    private fun sendItem(item: PickerItem) {
        dismiss()
        if (item.isGif) {
            if (onSendGif != null) {
                onSendGif.invoke(item.rawGif ?: SavedGifItem(item.fileId, item.filePath, 0, item.title))
            } else {
                if (item.fileId > 0) {
                    telegramManager.sendSavedAnimation(chat.id, item.fileId, item.rawGif?.durationSeconds ?: 0, item.title)
                } else if (!item.filePath.isNullOrBlank()) {
                    telegramManager.sendDocument(chat.id, item.filePath, item.title)
                } else {
                    telegramManager.sendMessage(chat.id, "🎞️ [${item.title}]")
                }
            }
        } else {
            if (onSendSticker != null) {
                onSendSticker.invoke(item.rawSticker ?: StickerItem(item.fileId, item.emoji, item.filePath, item.title))
            } else {
                if (item.fileId > 0) {
                    telegramManager.sendSticker(chat.id, item.fileId, item.emoji)
                } else if (item.emoji.isNotBlank()) {
                    telegramManager.sendStickerEmoji(chat.id, item.emoji)
                } else if (!item.filePath.isNullOrBlank()) {
                    telegramManager.sendLocalSticker(chat.id, item.filePath)
                } else {
                    telegramManager.sendMessage(chat.id, item.title)
                }
            }
        }
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false

        return when {
            SoftkeyUtils.isSoftLeftKey(keyCode) -> {
                prevPack()
                true
            }
            SoftkeyUtils.isSoftRightKey(keyCode) -> {
                nextPack()
                true
            }
            SoftkeyUtils.isCenterKey(keyCode) || keyCode == KeyEvent.KEYCODE_CALL -> {
                sendFocusedItem()
                true
            }
            keyCode == KeyEvent.KEYCODE_BACK -> {
                dismiss()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onBackPressed() {
        dismiss()
    }

    private class PickerGridAdapter(
        private var items: List<PickerItem>,
        private val onItemClick: (PickerItem) -> Unit,
    ) : RecyclerView.Adapter<PickerGridAdapter.PickerViewHolder>() {
        fun submitItems(newItems: List<PickerItem>) {
            this.items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): PickerViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_gif_sticker_picker, parent, false)
            return PickerViewHolder(view, onItemClick)
        }

        override fun onBindViewHolder(
            holder: PickerViewHolder,
            position: Int,
        ) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        class PickerViewHolder(
            itemView: View,
            private val onItemClick: (PickerItem) -> Unit,
        ) : RecyclerView.ViewHolder(itemView) {
            private val ivThumb: ImageView = itemView.findViewById(R.id.iv_picker_thumb)
            private val tvEmoji: TextView = itemView.findViewById(R.id.tv_picker_emoji)
            private val tvCaption: TextView = itemView.findViewById(R.id.tv_picker_caption)

            fun bind(item: PickerItem) {
                tvCaption.text = item.title

                val path = item.filePath
                if (!path.isNullOrBlank() && File(path).exists()) {
                    ivThumb.visibility = View.VISIBLE
                    tvEmoji.visibility = View.GONE
                    ivThumb.load(File(path))
                } else if (item.emoji.isNotBlank()) {
                    ivThumb.visibility = View.GONE
                    tvEmoji.visibility = View.VISIBLE
                    tvEmoji.text = item.emoji
                } else {
                    ivThumb.visibility = View.GONE
                    tvEmoji.visibility = View.VISIBLE
                    tvEmoji.text = if (item.isGif) "🎞️" else "🎨"
                }

                itemView.contentDescription = item.title
                itemView.setOnClickListener {
                    onItemClick(item)
                }
            }
        }
    }
}
