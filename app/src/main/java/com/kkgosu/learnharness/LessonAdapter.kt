package com.kkgosu.learnharness

import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.kkgosu.learnharness.LessonDisplayItem.FormulaDisplayItem
import com.kkgosu.learnharness.LessonDisplayItem.QuestionDisplayItem
import com.kkgosu.learnharness.LessonDisplayItem.Role
import com.kkgosu.learnharness.LessonDisplayItem.TextDisplayItem
import me.grishka.appkit.views.UsableRecyclerView

class LessonAdapter(private val markdown: io.noties.markwon.Markwon? = null) : UsableRecyclerView.Adapter<RecyclerView.ViewHolder>(null) {
    private var items: List<LessonDisplayItem> = emptyList()
    private var generating = false

    init { setHasStableIds(true) }

    // ponytail: the mock is append-only; use DiffUtil when real sessions support edits/removals.
    fun submit(next: List<LessonDisplayItem>, running: Boolean = false): Boolean {
        if (items === next && generating == running) return false
        val previous = items
        val wasGenerating = generating
        require(next.size >= previous.size && previous.indices.all { previous[it].id == next[it].id })
        items = next
        generating = running
        if (wasGenerating && !running) notifyItemRemoved(previous.size)
        previous.indices.forEach { index ->
            if (previous[index] != next[index]) notifyItemChanged(index, TEXT_CHANGED)
        }
        if (next.size > previous.size) notifyItemRangeInserted(previous.size, next.size - previous.size)
        if (!wasGenerating && running) notifyItemInserted(next.size)
        return true
    }

    override fun getItemCount() = items.size + if (generating) 1 else 0
    override fun getItemId(position: Int) = if (position == items.size) Long.MIN_VALUE else items[position].id
    override fun getItemViewType(position: Int) = if (position == items.size) R.layout.item_lesson_generating else when (items[position]) {
        is TextDisplayItem -> R.layout.item_lesson_text
        is FormulaDisplayItem -> R.layout.item_lesson_formula
        is QuestionDisplayItem -> R.layout.item_lesson_question
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(viewType, parent, false)
        return when (viewType) {
            R.layout.item_lesson_formula -> FormulaHolder(view)
            R.layout.item_lesson_question -> QuestionHolder(view)
            R.layout.item_lesson_generating -> object : RecyclerView.ViewHolder(view) {}
            else -> TextHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is TextHolder -> holder.bind(items[position] as TextDisplayItem)
            is FormulaHolder -> holder.bind(items[position] as FormulaDisplayItem)
            is QuestionHolder -> holder.bind(items[position] as QuestionDisplayItem)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (holder is TextHolder && TEXT_CHANGED in payloads) {
            holder.bind(items[position] as TextDisplayItem)
        } else onBindViewHolder(holder, position)
    }

    private inner class TextHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.item_text)
        private val label: TextView = view.findViewById(R.id.item_label)
        fun bind(item: TextDisplayItem) {
            text.setTextAppearance(if (item.role == Role.TITLE) R.style.TextAppearance_Harness_Title else R.style.TextAppearance_Harness_Prose)
            if (Build.VERSION.SDK_INT >= 28) text.isAccessibilityHeading = item.role == Role.TITLE
            label.visibility = if (item.role == Role.LEARNER || item.role == Role.QUESTION) View.VISIBLE else View.GONE
            label.setText(if (item.role == Role.QUESTION) R.string.runtime_question else R.string.your_note)
            if (markdown != null && item.role != Role.TITLE) markdown.setMarkdown(text, item.text) else text.text = item.text
        }
    }

    private class FormulaHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val text: TextView = view.findViewById(R.id.item_formula)
        fun bind(item: FormulaDisplayItem) {
            text.text = item.text
            text.contentDescription = item.spoken
            // A recycled equation must not inherit another equation's horizontal scroll.
            itemView.scrollTo(0, 0)
        }
    }

    private class QuestionHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val label: TextView = view.findViewById(R.id.item_answer_label)
        private val answer: TextView = view.findViewById(R.id.item_answer)
        private val thinking: TextView = view.findViewById(R.id.item_thinking)
        fun bind(item: QuestionDisplayItem) {
            label.text = item.confidence?.let { itemView.context.getString(R.string.your_answer_confidence, it) }
                ?: itemView.context.getString(R.string.your_answer)
            answer.text = item.answer
            thinking.text = item.thinking
            thinking.visibility = if (item.thinking.isBlank()) View.GONE else View.VISIBLE
        }
    }

    private companion object { const val TEXT_CHANGED = "text" }
}
