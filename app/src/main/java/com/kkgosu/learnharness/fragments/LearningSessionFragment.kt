package com.kkgosu.learnharness.fragments

import android.animation.ValueAnimator
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.transition.ChangeBounds
import android.transition.TransitionManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kkgosu.learnharness.MainActivity
import com.kkgosu.learnharness.R
import com.kkgosu.learnharness.controllers.MockLesson
import com.kkgosu.learnharness.ui.adapters.LessonAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.grishka.appkit.fragments.ToolbarFragment

class LearningSessionFragment : ToolbarFragment(R.layout.fragment_shell) {
    private var pageView: View? = null
    private val adapter = LessonAdapter()
    private var visibleScope: CoroutineScope? = null
    private var imeVisible = false
    private val lesson get() = (activity as MainActivity).mockLesson

    override fun onCreateContentView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        setTitle(R.string.sample_lesson_title)
        val views = inflater.inflate(R.layout.fragment_learning_session, container, false)
        pageView = views
        val model = lesson
        val list = views.findViewById<RecyclerView>(R.id.lesson_list)
        val options = views.findViewById<RadioGroup>(R.id.question_options)
        list.layoutManager = LinearLayoutManager(inflater.context)
        list.itemAnimator = null
        adapter.submit(model.state.value.items)
        list.adapter = adapter
        resources.getStringArray(R.array.lesson_options).forEachIndexed { index, text ->
            (options.getChildAt(index) as RadioButton).text = text
        }
        options.setOnCheckedChangeListener { group, id ->
            model.select((0 until group.childCount).indexOfFirst { group.getChildAt(it).id == id })
        }
        views.findViewById<EditText>(R.id.question_thinking).onEdit(model::thinking)
        views.findViewById<EditText>(R.id.lesson_composer).onEdit(model::draft)
        views.findViewById<SeekBar>(R.id.question_confidence)
            .setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) model.confidence(progress)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
            })
        views.findViewById<Button>(R.id.reveal_question).setOnClickListener { model.expand(true) }
        views.findViewById<Button>(R.id.collapse_question).setOnClickListener {
            dismissKeyboard(views)
            model.expand(false)
        }
        views.findViewById<Button>(R.id.submit_answer).setOnClickListener {
            dismissKeyboard(views)
            model.submitAnswer()
        }
        views.findViewById<Button>(R.id.composer_action).setOnClickListener {
            val state = model.state.value
            when {
                state.streaming -> model.stop()
                state.streamTarget.isNotEmpty() -> model.resume()
                else -> model.send()
            }
        }
        render(views, model.state.value)
        return views
    }

    // AppKit's visibility hooks also stop work when a stacked fragment is hidden, not just paused.
    override fun onShown() {
        super.onShown()
        val views = pageView ?: return
        val model = lesson
        visibleScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope ->
            scope.launch { model.state.collect { render(views, it) } }
            scope.launch {
                model.state.map { it.streaming }.distinctUntilChanged().collectLatest { running ->
                    if (running) model.stream()
                }
            }
        }
    }

    override fun onHidden() {
        visibleScope?.cancel()
        visibleScope = null
        super.onHidden()
    }

    @Suppress("DEPRECATION") // Native legacy insets on API 26–29; AppKit still owns all padding.
    override fun onApplyWindowInsets(insets: WindowInsets) {
        super.onApplyWindowInsets(insets)
        val visible = if (Build.VERSION.SDK_INT >= 30) insets.isVisible(WindowInsets.Type.ime())
        else insets.systemWindowInsetBottom > insets.stableInsetBottom
        if (imeVisible != visible) {
            imeVisible = visible
            pageView?.let { render(it, lesson.state.value) }
        }
    }

    override fun onDestroyView() {
        visibleScope?.cancel()
        visibleScope = null
        pageView?.findViewById<RecyclerView>(R.id.lesson_list)?.adapter = null
        pageView = null
        super.onDestroyView()
    }

    private fun render(views: View, state: MockLesson.State) = with(views) {
        val root = views as ViewGroup
        val lessonList = findViewById<RecyclerView>(R.id.lesson_list)
        val lessonStatus = findViewById<TextView>(R.id.lesson_status)
        val questionPanel = findViewById<View>(R.id.question_panel)
        val composerPanel = findViewById<View>(R.id.composer_panel)
        val dockShell = findViewById<View>(R.id.dock_shell)
        val revealQuestion = findViewById<Button>(R.id.reveal_question)
        val questionOptions = findViewById<RadioGroup>(R.id.question_options)
        val questionConfidence = findViewById<SeekBar>(R.id.question_confidence)
        val confidenceLabel = findViewById<TextView>(R.id.confidence_label)
        val questionThinking = findViewById<EditText>(R.id.question_thinking)
        val lessonComposer = findViewById<EditText>(R.id.lesson_composer)
        val submitAnswer = findViewById<Button>(R.id.submit_answer)
        val composerAction = findViewById<Button>(R.id.composer_action)
        val composerNotice = findViewById<TextView>(R.id.composer_notice)
        val followTail = !lessonList.canScrollVertically(1)
        if (adapter.submit(state.items) && followTail) {
            lessonList.post { lessonList.scrollToPosition(adapter.itemCount - 1) }
        }
        lessonStatus.setText(
            when {
                state.dock == MockLesson.Dock.QUESTION -> R.string.lesson_waiting
                state.streaming -> R.string.lesson_writing
                state.streamTarget.isNotEmpty() -> R.string.lesson_paused
                else -> R.string.lesson_preview
            }
        )
        val question = state.dock == MockLesson.Dock.QUESTION
        val expanded = question && state.expanded
        if (root.isLaidOut && ValueAnimator.areAnimatorsEnabled() &&
            ((questionPanel.visibility == View.VISIBLE) != expanded ||
                    (composerPanel.visibility == View.VISIBLE) == question)
        ) {
            TransitionManager.beginDelayedTransition(root, ChangeBounds().apply {
                duration = resources.getInteger(R.integer.motion_short_ms).toLong()
                addTarget(dockShell)
            })
        }
        // Give the editor the available height with an IME, especially at enlarged font scales.
        val editingAnswer = expanded && imeVisible
        lessonList.visibility = if (editingAnswer) View.GONE else View.VISIBLE
        lessonStatus.visibility = if (editingAnswer) View.GONE else View.VISIBLE
        questionPanel.visibility = if (expanded) View.VISIBLE else View.GONE
        revealQuestion.visibility = if (question && !expanded) View.VISIBLE else View.GONE
        composerPanel.visibility = if (question) View.GONE else View.VISIBLE
        val params = dockShell.layoutParams as LinearLayout.LayoutParams
        val height = if (expanded) 0 else ViewGroup.LayoutParams.WRAP_CONTENT
        val weight = if (expanded) 3f else 0f
        if (params.height != height || params.weight != weight) {
            params.height = height
            params.weight = weight
            dockShell.layoutParams = params
        }
        revealQuestion.setText(if (state.selection >= 0 || state.thinking.isNotEmpty()) R.string.resume_question else R.string.reveal_question)
        val checkedId =
            if (state.selection >= 0) questionOptions.getChildAt(state.selection).id else -1
        if (questionOptions.checkedRadioButtonId != checkedId) questionOptions.check(checkedId)
        val hasConfidence = state.selection != 3
        questionConfidence.isEnabled = hasConfidence
        questionConfidence.progress = state.confidence
        confidenceLabel.text =
            if (hasConfidence) getString(R.string.confidence_label, state.confidence)
            else getString(R.string.confidence_unavailable)
        questionThinking.restoreText(state.thinking)
        lessonComposer.restoreText(state.draft)
        submitAnswer.isEnabled = state.selection >= 0
        submitAnswer.alpha = if (submitAnswer.isEnabled) 1f else 0.4f
        composerAction.setText(
            when {
                state.streaming -> R.string.stop_stream
                state.streamTarget.isNotEmpty() -> R.string.resume_stream
                else -> R.string.send_note
            }
        )
        val atLimit = state.notes >= MockLesson.MAX_NOTES
        lessonComposer.isEnabled = !atLimit
        composerAction.isEnabled =
            state.streaming || state.streamTarget.isNotEmpty() || (!atLimit && state.draft.isNotBlank())
        composerAction.alpha = if (composerAction.isEnabled) 1f else 0.4f
        composerNotice.setText(if (atLimit) R.string.mock_limit else R.string.composer_preview_hint)
    }

    private fun dismissKeyboard(views: View) {
        views.context.getSystemService(InputMethodManager::class.java)
            .hideSoftInputFromWindow(views.windowToken, 0)
        views.requestFocus()
    }

    private fun EditText.restoreText(value: String) {
        if (text.toString() != value) setText(value)
    }

    private fun EditText.onEdit(action: (String) -> Unit) {
        addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(text: Editable?) {
                action(text.toString())
            }

            override fun beforeTextChanged(
                text: CharSequence?,
                start: Int,
                count: Int,
                after: Int
            ) = Unit

            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) =
                Unit
        })
    }
}
