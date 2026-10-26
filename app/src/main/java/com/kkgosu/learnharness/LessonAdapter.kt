package com.kkgosu.learnharness

import android.os.Build
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.ProgressBar
import android.widget.LinearLayout
import com.kkgosu.learnharness.LessonDisplayItem.AnsweredDisplayItem
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
    private val disclosures = mutableMapOf<Pair<Long, String>, Boolean>()

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
        is AnsweredDisplayItem -> R.layout.item_lesson_answered
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(viewType, parent, false)
        return when (viewType) {
            R.layout.item_lesson_formula -> FormulaHolder(view)
            R.layout.item_lesson_question -> QuestionHolder(view)
            R.layout.item_lesson_answered -> AnsweredHolder(view)
            R.layout.item_lesson_generating -> object : RecyclerView.ViewHolder(view) {}
            else -> TextHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is TextHolder -> holder.bind(items[position] as TextDisplayItem)
            is FormulaHolder -> holder.bind(items[position] as FormulaDisplayItem)
            is QuestionHolder -> holder.bind(items[position] as QuestionDisplayItem)
            is AnsweredHolder -> holder.bind(items[position] as AnsweredDisplayItem)
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
            val learner = item.role == Role.LEARNER
            val thinking = item.role == Role.THINKING
            val runtime = markdown != null
            val previous = items.getOrNull(bindingAdapterPosition - 1)
            itemView.findViewById<View>(R.id.item_divider).visibility = if (runtime && !learner &&
                (previous is AnsweredDisplayItem || previous is TextDisplayItem && previous.role == Role.LEARNER)) View.VISIBLE else View.GONE
            val band = itemView.findViewById<View>(R.id.item_band)
            band.setBackgroundResource(if (runtime && learner) R.drawable.learner_band else android.R.color.transparent)
            val inset = if (runtime && learner) itemView.resources.getDimensionPixelSize(R.dimen.space_16) else 0
            band.setPaddingRelative(inset, 0, inset, if (inset > 0) inset / 2 else 0)
            text.setTextColor(itemView.context.getColor(if (learner || thinking) R.color.muted_foreground else R.color.foreground))
            if (learner) text.setTypeface(text.typeface, Typeface.ITALIC)
            label.visibility = if (learner || thinking || item.role == Role.QUESTION) View.VISIBLE else View.GONE
            text.visibility = View.VISIBLE
            label.isEnabled = true
            if (learner || thinking) disclosure(label, text, item.id, "text",
                itemView.context.getString(if (thinking) R.string.transcript_agent_thinking else R.string.transcript_your_thinking), initiallyOpen = learner)
            else {
                label.setText(R.string.runtime_question)
                label.setOnClickListener(null)
                label.isEnabled = false
            }
            if (markdown != null && item.role != Role.TITLE) markdown.setMarkdown(text, item.text) else text.text = item.text
        }
    }

    private fun disclosure(toggle: TextView, body: View, id: Long, key: String, title: String, initiallyOpen: Boolean = false) {
        fun render() {
            val open = disclosures[id to key] ?: initiallyOpen
            body.visibility = if (open) View.VISIBLE else View.GONE
            toggle.text = toggle.context.getString(if (open) R.string.transcript_close else R.string.transcript_open, title)
            if (Build.VERSION.SDK_INT >= 30) toggle.stateDescription = toggle.context.getString(if (open) R.string.transcript_expanded else R.string.transcript_collapsed)
        }
        render()
        toggle.setOnClickListener { disclosures[id to key] = body.visibility != View.VISIBLE; render() }
    }

    private inner class AnsweredHolder(view: View) : RecyclerView.ViewHolder(view) {
        fun bind(item: AnsweredDisplayItem) = with(itemView) {
            val label = findViewById<TextView>(R.id.answered_label)
            val graded = item.correctAnswer.isNotBlank() && item.answer in item.choices
            val correct = item.answer == item.correctAnswer
            val title = context.getString(if (item.phase == "probe") R.string.transcript_probe else R.string.transcript_check)
            label.text = if (graded) "$title · ${context.getString(if (correct) R.string.transcript_correct else R.string.transcript_incorrect)}" else title
            label.setTextColor(context.getColor(if (graded) { if (correct) R.color.answer_correct else R.color.answer_incorrect } else R.color.muted_foreground))
            fun text(view: TextView, source: String) { if (markdown == null) view.text = source else markdown.setMarkdown(view, source) }
            text(findViewById(R.id.answered_question), item.question)
            val choices = findViewById<LinearLayout>(R.id.answered_choices)
            choices.removeAllViews()
            item.choices.forEach { choice ->
                val selected = choice == item.answer
                val key = choice == item.correctAnswer && item.correctAnswer.isNotBlank()
                val color = context.getColor(when { key -> R.color.answer_correct; selected && graded -> R.color.answer_incorrect; selected -> R.color.foreground; else -> R.color.border })
                choices.addView(TextView(context).apply {
                    setTextAppearance(R.style.TextAppearance_Harness_Prose)
                    setTextColor(context.getColor(if (selected || key) R.color.foreground else R.color.muted_foreground))
                    setTextIsSelectable(true)
                    val padding = resources.getDimensionPixelSize(R.dimen.space_12)
                    setPadding(padding, padding, padding, padding)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = resources.getDimensionPixelSize(R.dimen.space_8)
                    }
                    background = GradientDrawable().apply {
                        cornerRadius = resources.getDimension(R.dimen.space_12)
                        setStroke(resources.displayMetrics.density.toInt().coerceAtLeast(1), color)
                        setColor(if (selected) (color and 0x00FFFFFF) or 0x18000000 else context.getColor(R.color.surface))
                    }
                    text(this, (if (key) "✓  " else if (selected && graded) "✗  " else if (selected) "•  " else "") + choice)
                    contentDescription = when { key -> context.getString(R.string.transcript_key, choice); selected -> context.getString(R.string.transcript_selected, choice); else -> choice }
                })
            }
            findViewById<TextView>(R.id.answered_text).apply {
                visibility = if (item.answer in item.choices) View.GONE else View.VISIBLE
                text(this, item.answer)
            }
            findViewById<ProgressBar>(R.id.answered_confidence).apply {
                progress = item.confidence
                progressTintList = ColorStateList.valueOf(context.confidenceColor(item.confidence))
            }
            findViewById<TextView>(R.id.answered_confidence_label).text = context.getString(R.string.transcript_confidence, item.confidence)
            val thinking = findViewById<TextView>(R.id.answered_thinking)
            text(thinking, item.thinking)
            findViewById<TextView>(R.id.answered_thinking_toggle).apply {
                visibility = if (item.thinking.isBlank()) View.GONE else View.VISIBLE
                disclosure(this, thinking, item.id, "thinking", context.getString(R.string.transcript_your_thinking))
                if (item.thinking.isBlank()) thinking.visibility = View.GONE
            }
            val why = findViewById<TextView>(R.id.answered_why)
            text(why, listOf(item.correctAnswer, item.explanation).filter { it.isNotBlank() }.distinct().joinToString("\n\n"))
            findViewById<TextView>(R.id.answered_why_toggle).apply {
                visibility = if (item.correctAnswer.isBlank()) View.GONE else View.VISIBLE
                disclosure(this, why, item.id, "why", context.getString(if (item.choices.isEmpty()) R.string.transcript_reference else R.string.transcript_why),
                    initiallyOpen = item.choices.isEmpty())
                if (item.correctAnswer.isBlank()) why.visibility = View.GONE
            }
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

internal fun Context.confidenceColor(value: Int): Int = getColor(intArrayOf(
    R.color.confidence_0, R.color.confidence_1, R.color.confidence_2,
    R.color.confidence_3, R.color.confidence_4, R.color.confidence_5,
)[(value.coerceIn(0, 100) + 10) / 20])
