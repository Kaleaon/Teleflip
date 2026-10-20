package com.teleflip.app.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.tabs.TabLayout
import com.teleflip.app.R
import com.teleflip.app.model.SavedGifItem
import com.teleflip.app.model.StickerItem
import com.teleflip.app.model.StickerSetModel
import com.teleflip.app.telegram.TelegramManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

class MediaPickerBottomSheetDialog : BottomSheetDialogFragment() {
    private var chatId: Long = 0L
    private var telegramManager: TelegramManager? = null

    private lateinit var tabLayoutMedia: TabLayout
    private lateinit var etGifSearch: EditText
    private lateinit var tabLayoutStickerPacks: TabLayout
    private lateinit var rvMediaGrid: RecyclerView
    private lateinit var pbMediaLoading: ProgressBar
    private lateinit var tvEmptyState: TextView

    private var currentTabPosition: Int = TAB_STICKERS

    private var installedStickerSets: List<StickerSetModel> = emptyList()
    private var activeStickerPackIndex: Int = 0

    private var savedGifs: List<SavedGifItem> = emptyList()
    private var searchGifs: List<SavedGifItem> = emptyList()
    private var gifSearchJob: Job? = null

    private val fileUpdateListener = { file: TdApi.File ->
        if (file.local.isDownloadingCompleted && !file.local.path.isNullOrEmpty()) {
            activity?.runOnUiThread {
                updateCurrentGridAdapter()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chatId = arguments?.getLong(ARG_CHAT_ID, 0L) ?: 0L
        telegramManager = TelegramManager.getInstance(requireContext())
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        val view = inflater.inflate(R.layout.dialog_media_picker, container, false)

        tabLayoutMedia = view.findViewById(R.id.tab_layout_media)
        etGifSearch = view.findViewById(R.id.et_gif_search)
        tabLayoutStickerPacks = view.findViewById(R.id.tab_layout_sticker_packs)
        rvMediaGrid = view.findViewById(R.id.rv_media_grid)
        pbMediaLoading = view.findViewById(R.id.pb_media_loading)
        tvEmptyState = view.findViewById(R.id.tv_empty_state)

        setupMainTabs()
        setupStickerPackTabs()
        setupGifSearch()

        telegramManager?.addFileUpdateListener(fileUpdateListener)

        loadStickersTab()

        return view
    }

    override fun onDestroyView() {
        super.onDestroyView()
        telegramManager?.removeFileUpdateListener(fileUpdateListener)
        gifSearchJob?.cancel()
    }

    private fun setupMainTabs() {
        tabLayoutMedia.addTab(tabLayoutMedia.newTab().setText("Stickers"))
        tabLayoutMedia.addTab(tabLayoutMedia.newTab().setText("GIFs"))
        tabLayoutMedia.addTab(tabLayoutMedia.newTab().setText("Emojis"))

        tabLayoutMedia.addOnTabSelectedListener(
            object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab?) {
                    val position = tab?.position ?: 0
                    currentTabPosition = position
                    when (position) {
                        TAB_STICKERS -> {
                            etGifSearch.visibility = View.GONE
                            tabLayoutStickerPacks.visibility = View.VISIBLE
                            loadStickersTab()
                        }
                        TAB_GIFS -> {
                            etGifSearch.visibility = View.VISIBLE
                            tabLayoutStickerPacks.visibility = View.GONE
                            loadGifsTab()
                        }
                        TAB_EMOJIS -> {
                            etGifSearch.visibility = View.GONE
                            tabLayoutStickerPacks.visibility = View.GONE
                            loadEmojisTab()
                        }
                    }
                }

                override fun onTabUnselected(tab: TabLayout.Tab?) {}

                override fun onTabReselected(tab: TabLayout.Tab?) {}
            },
        )
    }

    private fun setupStickerPackTabs() {
        tabLayoutStickerPacks.addOnTabSelectedListener(
            object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab?) {
                    activeStickerPackIndex = tab?.position ?: 0
                    displayStickerPackGrid()
                }

                override fun onTabUnselected(tab: TabLayout.Tab?) {}

                override fun onTabReselected(tab: TabLayout.Tab?) {}
            },
        )
    }

    private fun setupGifSearch() {
        etGifSearch.addTextChangedListener(
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
                ) {}

                override fun afterTextChanged(s: Editable?) {
                    val query = s?.toString()?.trim() ?: ""
                    gifSearchJob?.cancel()
                    gifSearchJob =
                        lifecycleScope.launch {
                            delay(300)
                            performGifSearch(query)
                        }
                }
            },
        )
    }

    private fun loadStickersTab() {
        pbMediaLoading.visibility = View.VISIBLE
        tvEmptyState.visibility = View.GONE

        telegramManager?.loadInstalledStickers { sets ->
            pbMediaLoading.visibility = View.GONE
            installedStickerSets = sets

            tabLayoutStickerPacks.removeAllTabs()
            if (sets.isEmpty()) {
                tvEmptyState.text = "No installed stickers found"
                tvEmptyState.visibility = View.VISIBLE
                rvMediaGrid.adapter = null
            } else {
                tvEmptyState.visibility = View.GONE
                sets.forEach { set ->
                    tabLayoutStickerPacks.addTab(tabLayoutStickerPacks.newTab().setText(set.title))
                }
                activeStickerPackIndex = 0
                displayStickerPackGrid()
            }
        }
    }

    private fun displayStickerPackGrid() {
        if (installedStickerSets.isEmpty() || activeStickerPackIndex !in installedStickerSets.indices) {
            rvMediaGrid.adapter = null
            return
        }

        val pack = installedStickerSets[activeStickerPackIndex]
        rvMediaGrid.layoutManager = GridLayoutManager(requireContext(), 4)
        rvMediaGrid.adapter =
            StickerGridAdapter(pack.stickers) { item ->
                telegramManager?.sendSticker(chatId, item.fileId, item.emoji)
                Toast.makeText(requireContext(), "Sticker sent", Toast.LENGTH_SHORT).show()
                dismiss()
            }
        rvMediaGrid.post {
            rvMediaGrid.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }

    private fun loadGifsTab() {
        val query = etGifSearch.text.toString().trim()
        if (query.isNotEmpty()) {
            performGifSearch(query)
        } else {
            loadSavedGifs()
        }
    }

    private fun loadSavedGifs() {
        pbMediaLoading.visibility = View.VISIBLE
        tvEmptyState.visibility = View.GONE

        telegramManager?.loadSavedGifs { gifs ->
            pbMediaLoading.visibility = View.GONE
            savedGifs = gifs
            if (gifs.isEmpty()) {
                loadReactionGifsFallback()
            } else {
                displayGifGrid(gifs)
            }
        }
    }

    private fun performGifSearch(query: String) {
        if (query.isEmpty()) {
            loadSavedGifs()
            return
        }

        pbMediaLoading.visibility = View.VISIBLE
        tvEmptyState.visibility = View.GONE

        telegramManager?.searchInlineGifs(query, chatId) { results ->
            pbMediaLoading.visibility = View.GONE
            searchGifs = results
            if (results.isEmpty()) {
                // Fall back to saved GIFs or reaction loops if search yields no results
                if (savedGifs.isNotEmpty()) {
                    displayGifGrid(savedGifs)
                } else {
                    loadReactionGifsFallback()
                }
            } else {
                displayGifGrid(results)
            }
        }
    }

    private fun loadReactionGifsFallback() {
        val reactions =
            listOf(
                SavedGifItem(fileId = 1, caption = "🎉 Celebration"),
                SavedGifItem(fileId = 2, caption = "👋 Wave / Hello"),
                SavedGifItem(fileId = 3, caption = "👏 Applause"),
                SavedGifItem(fileId = 4, caption = "🤣 Laughter"),
                SavedGifItem(fileId = 5, caption = "👍 Thumbs Up"),
                SavedGifItem(fileId = 6, caption = "🔥 Fire"),
                SavedGifItem(fileId = 7, caption = "💃 Dance"),
                SavedGifItem(fileId = 8, caption = "🤝 Deal"),
            )
        displayGifGrid(reactions)
    }

    private fun displayGifGrid(gifs: List<SavedGifItem>) {
        if (gifs.isEmpty()) {
            tvEmptyState.text = "No GIFs found"
            tvEmptyState.visibility = View.VISIBLE
            rvMediaGrid.adapter = null
            return
        }

        tvEmptyState.visibility = View.GONE
        rvMediaGrid.layoutManager = GridLayoutManager(requireContext(), 2)
        rvMediaGrid.adapter =
            GifGridAdapter(gifs) { item ->
                if (item.queryId != 0L && item.resultId.isNotEmpty()) {
                    telegramManager?.sendInlineQueryResult(chatId, item.queryId, item.resultId)
                } else if (item.filePath != null || item.fileId > 10) {
                    telegramManager?.sendSavedAnimation(chatId, item.fileId, item.durationSeconds, item.caption)
                } else {
                    telegramManager?.sendMessage(chatId, "🎞️ [${item.caption}]")
                }
                Toast.makeText(requireContext(), "GIF sent", Toast.LENGTH_SHORT).show()
                dismiss()
            }
        rvMediaGrid.post {
            rvMediaGrid.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }

    private fun loadEmojisTab() {
        val symbols =
            listOf(
                "😊", "😂", "👍", "❤️", "🔥", "🎉", "👏", "🙏",
                "🥰", "😍", "🤣", "✨", "💯", "😎", "🤔", "🥳",
                "?", "!", "@", "#", "$", "%", "&", "*",
                "(", ")", "-", "+", "=", "/", ":", ";",
                "\"", "'", "<", ">", ",", ".", "~", "_",
            )
        pbMediaLoading.visibility = View.GONE
        tvEmptyState.visibility = View.GONE

        rvMediaGrid.layoutManager = GridLayoutManager(requireContext(), 4)
        rvMediaGrid.adapter =
            SymbolGridAdapter(symbols) { symbol ->
                telegramManager?.sendStickerEmoji(chatId, symbol)
                Toast.makeText(requireContext(), "Emoji $symbol sent", Toast.LENGTH_SHORT).show()
                dismiss()
            }
        rvMediaGrid.post {
            rvMediaGrid.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }

    private fun updateCurrentGridAdapter() {
        when (currentTabPosition) {
            TAB_STICKERS -> displayStickerPackGrid()
            TAB_GIFS -> loadGifsTab()
            else -> {}
        }
    }

    inner class StickerGridAdapter(
        private val stickers: List<StickerItem>,
        private val onItemClick: (StickerItem) -> Unit,
    ) : RecyclerView.Adapter<StickerGridAdapter.ViewHolder>() {
        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_sticker_grid, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(
            holder: ViewHolder,
            position: Int,
        ) {
            val sticker = stickers[position]
            val path = sticker.filePath
            holder.itemView.contentDescription = sticker.emoji.ifBlank { "Sticker ${position + 1}" }

            if (!path.isNullOrEmpty()) {
                holder.ivSticker.loadMediaFromFile(path)
                holder.ivSticker.visibility = View.VISIBLE
                holder.tvEmojiFallback.visibility = View.GONE
            } else if (sticker.emoji.isNotEmpty()) {
                holder.ivSticker.visibility = View.GONE
                holder.tvEmojiFallback.visibility = View.VISIBLE
                holder.tvEmojiFallback.text = sticker.emoji
            } else {
                holder.ivSticker.visibility = View.GONE
                holder.tvEmojiFallback.visibility = View.VISIBLE
                holder.tvEmojiFallback.text = "🖼️"
            }

            holder.itemView.setOnClickListener {
                onItemClick(sticker)
            }
        }

        override fun getItemCount(): Int = stickers.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ivSticker: ImageView = view.findViewById(R.id.iv_sticker_image)
            val tvEmojiFallback: TextView = view.findViewById(R.id.tv_sticker_emoji_fallback)
        }
    }

    inner class GifGridAdapter(
        private val gifs: List<SavedGifItem>,
        private val onItemClick: (SavedGifItem) -> Unit,
    ) : RecyclerView.Adapter<GifGridAdapter.ViewHolder>() {
        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_gif_grid, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(
            holder: ViewHolder,
            position: Int,
        ) {
            val gif = gifs[position]
            holder.itemView.contentDescription = gif.caption.ifBlank { "GIF ${position + 1}" }
            holder.tvCaption.text = gif.caption.ifBlank { "GIF ${position + 1}" }

            if (!gif.filePath.isNullOrEmpty()) {
                holder.ivGif.loadMediaFromFile(gif.filePath)
            } else {
                holder.ivGif.setImageResource(android.R.drawable.ic_menu_gallery)
            }

            holder.itemView.setOnClickListener {
                onItemClick(gif)
            }
        }

        override fun getItemCount(): Int = gifs.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ivGif: ImageView = view.findViewById(R.id.iv_gif_thumbnail)
            val tvCaption: TextView = view.findViewById(R.id.tv_gif_caption)
        }
    }

    inner class SymbolGridAdapter(
        private val symbols: List<String>,
        private val onItemClick: (String) -> Unit,
    ) : RecyclerView.Adapter<SymbolGridAdapter.ViewHolder>() {
        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.dialog_symbol_item, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(
            holder: ViewHolder,
            position: Int,
        ) {
            val symbol = symbols[position]
            holder.tvSymbol.text = symbol
            holder.itemView.contentDescription = symbol
            holder.itemView.setOnClickListener {
                onItemClick(symbol)
            }
        }

        override fun getItemCount(): Int = symbols.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvSymbol: TextView = view.findViewById(R.id.tv_symbol_text)
        }
    }

    companion object {
        private const val ARG_CHAT_ID = "arg_chat_id"
        private const val TAB_STICKERS = 0
        private const val TAB_GIFS = 1
        private const val TAB_EMOJIS = 2

        fun newInstance(chatId: Long): MediaPickerBottomSheetDialog {
            val dialog = MediaPickerBottomSheetDialog()
            val args =
                Bundle().apply {
                    putLong(ARG_CHAT_ID, chatId)
                }
            dialog.arguments = args
            return dialog
        }
    }
}
