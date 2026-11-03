package com.teleflip.app.ui

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
    initialPage: Int = 0,
) : BaseDialog(context) {
    internal var currentPage: Int = initialPage.coerceIn(0, ((options.size - 1).coerceAtLeast(0) / 9))
        private set
    private val pageCount get() = ((options.size + PAGE_SIZE - 1) / PAGE_SIZE).coerceAtLeast(1)
    private val visibleOptions get() = options.drop(currentPage * PAGE_SIZE).take(PAGE_SIZE)

    override fun onStart() {
        super.onStart()
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun updatePage() {
        findViewById<TextView>(R.id.tv_menu_title)?.text = if (pageCount > 1) "$title • ${currentPage + 1}/$pageCount" else title
        findViewById<TextView>(R.id.tv_menu_hint)?.text = if (pageCount > 1) "◄/►: Pages • 1–9: Select • RSK: Back" else "1–9: Select • RSK: Back"
        findViewById<RecyclerView>(R.id.rv_menu_options)?.let { rv ->
            rv.adapter?.notifyDataSetChanged()
            rv.scrollToPosition(0)
            rv.post { rv.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_options_menu)
        setupDismissKeys()

        findViewById<TextView>(R.id.tv_menu_title)?.text = title

        val rv = findViewById<RecyclerView>(R.id.rv_menu_options)
        rv?.layoutManager = LinearLayoutManager(context)
        rv?.adapter = MenuAdapter()

        updatePage()
        // Focus the first option
        rv?.post {
            rv.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }

    override fun onBackPressed() {
        dismiss()
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        if (pageCount > 1 && (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_POUND || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_STAR)) {
            if (event.repeatCount == 0) {
                val step = if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_POUND) 1 else -1
                currentPage = (currentPage + step + pageCount) % pageCount
                updatePage()
            }
            return true
        }
        // Direct number selection (1-9)
        if (keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9) {
            val index = keyCode - KeyEvent.KEYCODE_1
            if (event.repeatCount > 0) return true
            if (index in visibleOptions.indices) {
                val option = visibleOptions[index]
                dismiss()
                option.action.invoke()
                return true
            }
        }

        // Back or Soft Right closes menu
        if (isBackOrRightSoftKey(keyCode)) {
            dismiss()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        private const val PAGE_SIZE = 9
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
            val opt = visibleOptions[position]
            holder.tvShortcut.text = "${position + 1}."
            holder.tvLabel.text = opt.title
            holder.itemView.contentDescription = "${position + 1}. ${opt.title}"
            holder.itemView.setOnClickListener {
                dismiss()
                opt.action.invoke()
            }
        }

        override fun getItemCount(): Int = visibleOptions.size

        inner class OptionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvShortcut: TextView = view.findViewById(R.id.tv_option_shortcut)
            val tvLabel: TextView = view.findViewById(R.id.tv_option_label)
        }
    }
}
