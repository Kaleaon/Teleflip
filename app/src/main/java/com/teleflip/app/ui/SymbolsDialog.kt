package com.teleflip.app.ui

import android.app.Dialog
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
    private val onSymbolSelected: (String) -> Unit
) : Dialog(context, R.style.Theme_TeleFlip) {

    private val symbols = listOf(
        "?", "!", "@", "#", "$", "%", "&", "*",
        "(", ")", "-", "+", "=", "/", ":", ";",
        "\"", "'", "<", ">", ",", ".", "~", "_",
        "😊", "😂", "👍", "❤️", "🔥", "🎉", "👏", "🙏"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_symbols)

        val rv = findViewById<RecyclerView>(R.id.rv_symbols_grid)
        rv?.layoutManager = GridLayoutManager(context, 4)
        rv?.adapter = SymbolAdapter()

        rv?.post {
            rv.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_SOFT_RIGHT) {
            dismiss()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    inner class SymbolAdapter : RecyclerView.Adapter<SymbolAdapter.SymbolViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SymbolViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.dialog_symbol_item, parent, false)
            return SymbolViewHolder(view)
        }

        override fun onBindViewHolder(holder: SymbolViewHolder, position: Int) {
            val symbol = symbols[position]
            holder.tvSymbol.text = symbol
            holder.itemView.setOnClickListener {
                dismiss()
                onSymbolSelected(symbol)
            }
        }

        override fun getItemCount(): Int = symbols.size

        inner class SymbolViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvSymbol: TextView = view.findViewById(R.id.tv_symbol_text)
        }
    }
}
