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

    private val pageSize = 9
    private var currentPage = 0
    private val totalPages: Int
        get() = (symbols.size + pageSize - 1) / pageSize

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
        rvGrid?.layoutManager = GridLayoutManager(context, 3)
        rvGrid?.adapter = adapter

        val tvLeft = findViewById<TextView>(R.id.tv_softkey_left)
        val tvCenter = findViewById<TextView>(R.id.tv_softkey_center)
        val tvRight = findViewById<TextView>(R.id.tv_softkey_right)
        val softkeyBar = findViewById<View>(R.id.softkey_bar)

        if (tvLeft != null && tvCenter != null && tvRight != null) {
            softkeyManager = SoftkeyManager(softkeyBar, tvLeft, tvCenter, tvRight)
            softkeyManager?.update(
                left = "Page",
                center = "Select",
                right = "Back",
                onLeft = { nextPage() },
                onCenter = { selectCurrentFocusedSymbol() },
                onRight = { dismiss() },
            )
        }

        updatePageUI()
    }

    private fun getCurrentPageSymbols(): List<String> {
        val startIndex = currentPage * pageSize
        val endIndex = minOf(startIndex + pageSize, symbols.size)
        return if (startIndex < symbols.size) symbols.subList(startIndex, endIndex) else emptyList()
    }

    private fun updatePageUI() {
        val currentSymbols = getCurrentPageSymbols()
        tvHeader?.text = "Page ${currentPage + 1}/$totalPages (1-${currentSymbols.size})"
        adapter?.notifyDataSetChanged()
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

    private fun selectCurrentFocusedSymbol() {
        val currentSymbols = getCurrentPageSymbols()
        if (currentSymbols.isEmpty()) return

        val focusedChild = rvGrid?.focusedChild
        var selectedIndex = 0
        if (focusedChild != null) {
            val pos = rvGrid?.getChildAdapterPosition(focusedChild) ?: RecyclerView.NO_POSITION
            if (pos != RecyclerView.NO_POSITION && pos in currentSymbols.indices) {
                selectedIndex = pos
            }
        }
        dismiss()
        onSymbolSelected(currentSymbols[selectedIndex])
    }

    override fun onBackPressed() {
        dismiss()
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        if (keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9) {
            val index = keyCode - KeyEvent.KEYCODE_1
            val currentSymbols = getCurrentPageSymbols()
            if (index in currentSymbols.indices) {
                dismiss()
                onSymbolSelected(currentSymbols[index])
            }
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_STAR) {
            previousPage()
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_POUND) {
            nextPage()
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
            holder.tvShortcut.text = "${position + 1}."
            holder.tvSymbol.text = symbol
            holder.itemView.contentDescription = "${position + 1}. $symbol"
            holder.itemView.setOnClickListener {
                dismiss()
                onSymbolSelected(symbol)
            }
        }

        override fun getItemCount(): Int = getCurrentPageSymbols().size

        inner class SymbolViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvShortcut: TextView = view.findViewById(R.id.tv_symbol_shortcut)
            val tvSymbol: TextView = view.findViewById(R.id.tv_symbol_text)
        }
    }
}
