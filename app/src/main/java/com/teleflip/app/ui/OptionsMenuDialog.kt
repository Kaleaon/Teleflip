package com.teleflip.app.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.teleflip.app.R

data class MenuOption(
    val id: Int,
    val title: String,
    val action: () -> Unit,
)

class OptionsMenuDialog(
    context: Context,
    private val title: String,
    private val options: List<MenuOption>,
) : Dialog(context, R.style.Theme_TeleFlip) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_options_menu)

        findViewById<TextView>(R.id.tv_menu_title)?.text = title

        val rv = findViewById<RecyclerView>(R.id.rv_menu_options)
        rv?.layoutManager = LinearLayoutManager(context)
        rv?.adapter = MenuAdapter()

        // Focus the first option
        rv?.post {
            rv.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        // Direct number selection (1-9)
        if (keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9) {
            val index = keyCode - KeyEvent.KEYCODE_1
            if (index in options.indices) {
                dismiss()
                options[index].action.invoke()
                return true
            }
        }

        // Back or Soft Right closes menu
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_SOFT_RIGHT) {
            dismiss()
            return true
        }

        return super.onKeyDown(keyCode, event)
    }

    inner class MenuAdapter : RecyclerView.Adapter<MenuAdapter.OptionViewHolder>() {
        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): OptionViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.dialog_option_item, parent, false)
            return OptionViewHolder(view)
        }

        override fun onBindViewHolder(
            holder: OptionViewHolder,
            position: Int,
        ) {
            val opt = options[position]
            holder.tvShortcut.text = "${position + 1}."
            holder.tvLabel.text = opt.title
            holder.itemView.setOnClickListener {
                dismiss()
                opt.action.invoke()
            }
        }

        override fun getItemCount(): Int = options.size

        inner class OptionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvShortcut: TextView = view.findViewById(R.id.tv_option_shortcut)
            val tvLabel: TextView = view.findViewById(R.id.tv_option_label)
        }
    }
}
