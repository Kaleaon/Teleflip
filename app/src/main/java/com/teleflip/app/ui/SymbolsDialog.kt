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
        private set

    internal val pageCount: Int
        get() = ((symbols.size + PAGE_SIZE - 1) / PAGE_SIZE).coerceAtLeast(1)

    internal val visibleSymbols: List<String>
        get() = symbols.drop(currentPage * PAGE_SIZE).take(PAGE_SIZE)

    private var softkeyManager: SoftkeyManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_symbols)
        setupDismissKeys()

        val softkeyBar = findViewById<View>(R.id.softkey_bar)
        val tvLeft = findViewById<TextView>(R.id.tv_softkey_left)
        val tvCenter = findViewById<TextView>(R.id.tv_softkey_center)
        val tvRight = findViewById<TextView>(R.id.tv_softkey_right)

        if (tvLeft != null && tvCenter != null && tvRight != null) {
            softkeyManager = SoftkeyManager(softkeyBar, tvLeft, tvCenter, tvRight).apply {
                update(
                    left = "Next",
                    center = "Select",
                    right = "Cancel",
                    onLeft = { nextPage() },
                    onCenter = { selectFocusedSymbol() },
                    onRight = { dismiss() },
                )
            }
        }

        val rv = findViewById<RecyclerView>(R.id.rv_symbols_grid)
        rv?.layoutManager = GridLayoutManager(context, COLUMNS)
        rv?.adapter = SymbolAdapter()

        updatePage()
    }

    override fun onBackPressed() {
        dismiss()
    }

    private fun nextPage() {
        currentPage = (currentPage + 1) % pageCount
        updatePage()
    }

    private fun prevPage() {
        currentPage = (currentPage - 1 + pageCount) % pageCount
        updatePage()
    }

    private fun updatePage() {
        findViewById<TextView>(R.id.tv_symbols_title)?.text = "Symbols (${currentPage + 1}/$pageCount)"

        val rv = findViewById<RecyclerView>(R.id.rv_symbols_grid)
        rv?.adapter?.notifyDataSetChanged()
        rv?.scrollToPosition(0)
        rv?.post {
            rv.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }

    private fun selectSymbol(symbol: String) {
        dismiss()
        onSymbolSelected(symbol)
    }

    private fun selectFocusedSymbol() {
        val rv = findViewById<RecyclerView>(R.id.rv_symbols_grid)
        val focusedView = rv?.findFocus()
        if (rv != null && focusedView != null) {
            val viewHolder = rv.findContainingViewHolder(focusedView)
            if (viewHolder != null) {
                val pos = viewHolder.bindingAdapterPosition
                if (pos in visibleSymbols.indices) {
                    selectSymbol(visibleSymbols[pos])
                    return
                }
            }
        }
        if (visibleSymbols.isNotEmpty()) {
            selectSymbol(visibleSymbols[0])
        }
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        if (softkeyManager?.handleKeyEvent(keyCode, event) == true) {
            return true
        }

        if (keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_8) {
            if (event.repeatCount > 0) return true
            val index = keyCode - KeyEvent.KEYCODE_1
            if (index in visibleSymbols.indices) {
                selectSymbol(visibleSymbols[index])
                return true
            }
        }

        if (keyCode == KeyEvent.KEYCODE_POUND) {
            if (event.repeatCount == 0) {
                nextPage()
            }
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_STAR) {
            if (event.repeatCount == 0) {
                prevPage()
            }
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            val rv = findViewById<RecyclerView>(R.id.rv_symbols_grid)
            val focusedView = rv?.findFocus()
            if (rv != null && focusedView != null) {
                val viewHolder = rv.findContainingViewHolder(focusedView)
                if (viewHolder != null) {
                    val pos = viewHolder.bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        val col = pos % COLUMNS
                        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && col == COLUMNS - 1) {
                            if (event.repeatCount == 0) {
                                nextPage()
                            }
                            return true
                        }
                        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && col == 0) {
                            if (event.repeatCount == 0) {
                                prevPage()
                            }
                            return true
                        }
                    }
                }
            }
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
            val symbol = visibleSymbols[position]
            val shortcutNum = position + 1
            holder.tvSymbol.text = symbol
            holder.tvShortcutBadge.text = shortcutNum.toString()
            holder.itemView.contentDescription = "$shortcutNum. $symbol"
            holder.itemView.setOnClickListener {
                selectSymbol(symbol)
            }
        }

        override fun getItemCount(): Int = visibleSymbols.size

        inner class SymbolViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvSymbol: TextView = view.findViewById(R.id.tv_symbol_text)
            val tvShortcutBadge: TextView = view.findViewById(R.id.tv_shortcut_badge)
        }
    }

    companion object {
        private const val PAGE_SIZE = 8
        private const val COLUMNS = 4
    }
}
