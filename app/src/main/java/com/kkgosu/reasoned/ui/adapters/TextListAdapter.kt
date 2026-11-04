package com.kkgosu.reasoned.ui.adapters

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckedTextView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kkgosu.reasoned.R
import me.grishka.appkit.views.UsableRecyclerView

/** Text rows shared by the drawer, graph search and choice dialogs. */
class TextListAdapter<T>(
    items: List<T>,
    private val layout: Int = R.layout.item_session_navigation,
    private val textId: Int = 0,
    private val isChecked: (Int) -> Boolean = { false },
    var onClick: ((Int) -> Unit)? = null,
    private val onLongClick: ((Int, View) -> Boolean)? = null
) : UsableRecyclerView.Adapter<TextListAdapter<T>.Holder>(null) {
    var items = items
        private set

    fun submit(next: List<T>) {
        items = next
        notifyDataSetChanged()
    }

    fun createView(context: Context) = UsableRecyclerView(context).apply {
        id = R.id.choice_list
        layoutManager = LinearLayoutManager(context)
        adapter = this@TextListAdapter
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(layout, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.text.text = items[position].toString()
        holder.itemView.isActivated = isChecked(position)
        (holder.text as? CheckedTextView)?.isChecked = isChecked(position)
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = if (textId == 0) view as TextView else view.findViewById(textId)

        init {
            // Native View clicks retain keyboard/TalkBack actions as well as touch feedback.
            if (onClick != null || onLongClick != null) {
                view.context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).let {
                    view.foreground = it.getDrawable(0)
                    it.recycle()
                }
                view.isFocusable = true
            }
            if (onClick != null) view.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onClick?.invoke(position)
                    if (position < itemCount) notifyItemChanged(position)
                }
            }
            if (onLongClick != null) view.setOnLongClickListener {
                val position = bindingAdapterPosition
                position != RecyclerView.NO_POSITION && onLongClick.invoke(position, view)
            }
        }
    }
}
