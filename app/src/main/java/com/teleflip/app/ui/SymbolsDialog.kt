package com.teleflip.app.ui

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.teleflip.app.R

class SymbolsDialog(
    context: Context,
    private val onSymbolSelected: (String) -> Unit,
) : BaseDialog(context) {
    private val symbols =
        listOf(
            "?", "!", "@", "#", "$", "%", "&", "*",
            "(", ")", "-", "+", "=", "/", ":", ";",
            "\"", "'", "<", ">", ",", ".", "~", "_",
            "😊", "😂", "👍", "❤️", "🔥", "🎉", "👏", "🙏",
        )

    internal var currentPage: Int = 0
        internal set

    internal val pageCount: Int
        get() = totalPages

    internal val totalPages: Int
        get() = (symbols.size + PAGE_SIZE - 1) / PAGE_SIZE

    internal val visibleSymbols: List<String>
        get() = getCurrentPageSymbols()

    private var softkeyManager: SoftkeyManager? = null
    private var adapter: SymbolAdapter? = null
    private var tvHeader: TextView? = null
    private var rvGrid: RecyclerView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_symbols)
        setupDismissKeys()

        tvHeader = findViewById(R.id.tv_symbols_header)
        rvGrid = findViewById(R.id.rv_symbols_grid)

        adapter = SymbolAdapter()
        rvGrid?.layoutManager = GridLayoutManager(context, COLUMNS)
        rvGrid?.adapter = adapter

        val tvLeft = findViewById<TextView>(R.id.tv_softkey_left)
        val tvCenter = findViewById<TextView>(R.id.tv_softkey_center)
        val tvRight = findViewById<TextView>(R.id.tv_softkey_right)
        val softkeyBar = findViewById<View>(R.id.softkey_bar)

        if (tvLeft != null && tvCenter != null && tvRight != null) {
            softkeyManager =
                SoftkeyManager(softkeyBar, tvLeft, tvCenter, tvRight).apply {
                    update(
                        left = "Page",
                        center = "Select",
                        right = "Back",
                        onLeft = { nextPage() },
                        onCenter = { selectCurrentFocusedSymbol() },
                        onRight = { dismiss() },
                    )
                }
        }

        updatePageUI()
    }

    override fun onBackPressed() {
        dismiss()
    }

    internal fun getCurrentPageSymbols(): List<String> {
        val startIndex = currentPage * PAGE_SIZE
        val endIndex = minOf(startIndex + PAGE_SIZE, symbols.size)
        return if (startIndex < symbols.size) symbols.subList(startIndex, endIndex) else emptyList()
    }

    private fun updatePageUI() {
        val currentSymbols = getCurrentPageSymbols()
        tvHeader?.text = "Page ${currentPage + 1}/$totalPages (1-${currentSymbols.size})"
        adapter?.notifyDataSetChanged()
        rvGrid?.scrollToPosition(0)
        rvGrid?.post {
            rvGrid?.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }

    private fun nextPage() {
        currentPage = (currentPage + 1) % totalPages
        updatePageUI()
    }

    private fun previousPage() {
        currentPage = (currentPage - 1 + totalPages) % totalPages
        updatePageUI()
    }

    private fun selectSymbol(symbol: String) {
        dismiss()
        onSymbolSelected(symbol)
    }

    private fun selectCurrentFocusedSymbol() {
        val currentSymbols = getCurrentPageSymbols()
        if (currentSymbols.isEmpty()) return

        val rv = rvGrid
        val focusedView = rv?.findFocus()
        if (rv != null && focusedView != null) {
            val viewHolder = rv.findContainingViewHolder(focusedView)
            if (viewHolder != null) {
                val pos = viewHolder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION && pos in currentSymbols.indices) {
                    selectSymbol(currentSymbols[pos])
                    return
                }
            }
        }
        selectSymbol(currentSymbols[0])
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        if (keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9) {
            if (event.repeatCount > 0) return true
            val index = keyCode - KeyEvent.KEYCODE_1
            val currentSymbols = getCurrentPageSymbols()
            if (index in currentSymbols.indices) {
                selectSymbol(currentSymbols[index])
            }
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_STAR) {
            if (event.repeatCount == 0) {
                previousPage()
            }
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_POUND) {
            if (event.repeatCount == 0) {
                nextPage()
            }
            return true
        }

        if (softkeyManager?.handleKeyEvent(keyCode, event) == true) {
            return true
        }

        if (isBackOrRightSoftKey(keyCode)) {
            dismiss()
            return true
        }

        return super.onKeyDown(keyCode, event)
    }

    inner class SymbolAdapter : RecyclerView.Adapter<SymbolAdapter.SymbolViewHolder>() {
        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): SymbolViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.dialog_symbol_item, parent, false)
            return SymbolViewHolder(view)
        }

        override fun onBindViewHolder(
            holder: SymbolViewHolder,
            position: Int,
        ) {
            val currentSymbols = getCurrentPageSymbols()
            if (position !in currentSymbols.indices) return
            val symbol = currentSymbols[position]
            val shortcutBadge = "${position + 1}."
            holder.tvShortcut?.text = shortcutBadge
            holder.tvSymbol.text = symbol
            holder.itemView.contentDescription = "$shortcutBadge $symbol"
            holder.itemView.setOnClickListener {
                selectSymbol(symbol)
            }
        }

        override fun getItemCount(): Int = getCurrentPageSymbols().size

        inner class SymbolViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvShortcut: TextView? = view.findViewById(R.id.tv_symbol_shortcut)
            val tvSymbol: TextView = view.findViewById(R.id.tv_symbol_text)
            val tvShortcutBadge: TextView? get() = tvShortcut
        }
    }

    companion object {
        const val PAGE_SIZE = 9
        const val COLUMNS = 3
    }
}
