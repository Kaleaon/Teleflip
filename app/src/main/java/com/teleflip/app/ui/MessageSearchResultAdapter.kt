package com.teleflip.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.teleflip.app.R
import com.teleflip.app.accessibility.AccessibilityActionManager
import com.teleflip.app.model.MessageSearchResult

class MessageSearchResultAdapter(
    private val onItemClick: (MessageSearchResult) -> Unit,
    private val onSearchResultAccessibilityAction: ((actionId: Int, result: MessageSearchResult) -> Boolean)? = null,
) : RecyclerView.Adapter<MessageSearchResultAdapter.ViewHolder>() {
    private val items = mutableListOf<MessageSearchResult>()

    fun submitList(newItems: List<MessageSearchResult>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun addMoreItems(moreItems: List<MessageSearchResult>) {
        val start = items.size
        items.addAll(moreItems)
        notifyItemRangeInserted(start, moreItems.size)
    }

    fun getItemAt(position: Int): MessageSearchResult? {
        return items.getOrNull(position)
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.view_search_message_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) {
        val item = items[position]
        holder.bind(item)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        AccessibilityActionManager.clearActions(holder.itemView)
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.tv_search_chat_title)
        private val tvTime: TextView = itemView.findViewById(R.id.tv_search_message_time)
        private val tvSnippet: TextView = itemView.findViewById(R.id.tv_search_message_snippet)

        fun bind(item: MessageSearchResult) {
            val titleText =
                if (item.senderName.isNotBlank() && item.senderName != "You" && item.senderName != "Contact") {
                    "${item.chatTitle} (${item.senderName})"
                } else {
                    item.chatTitle
                }
            tvTitle.text = titleText
            tvTime.text = item.time
            tvSnippet.text = item.text

            itemView.setOnClickListener {
                onItemClick(item)
            }

            AccessibilityActionManager.bindActions(itemView, item) { actionId, searchResult ->
                if (actionId == R.id.action_search_jump) {
                    onItemClick(searchResult)
                    true
                } else {
                    onSearchResultAccessibilityAction?.invoke(actionId, searchResult) ?: false
                }
            }
        }
    }
}
