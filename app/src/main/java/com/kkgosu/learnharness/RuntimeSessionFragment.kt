package com.kkgosu.learnharness

import android.animation.ValueAnimator
import android.app.AlertDialog
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.noties.markwon.Markwon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.grishka.appkit.Nav
import me.grishka.appkit.fragments.ToolbarFragment

/** App-owned runtime with a native transcript, question panel and persistent composer. */
class RuntimeSessionFragment : ToolbarFragment(R.layout.fragment_shell) {
    private var pageView: View? = null
    private var adapter = LessonAdapter()
    private var scope: CoroutineScope? = null
    private var dialog: AlertDialog? = null
    private var markdown: Markwon? = null
    private var rendering = false
    private var loadingModels = false
    private var imeAnimations = 0
    private val app get() = activity.application as ReasonedApplication
    private val runtime get() = app.runtime
    private val sessionId get() = arguments!!.getString("session")!!

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setHasOptionsMenu(true)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.runtime_session, menu)
        val state = runtime.state.value?.takeIf { it.id == sessionId }
        menu.findItem(R.id.practice_dismiss).apply {
            isVisible = state?.activeProblem != null; isEnabled = state?.status != "running"
        }
        menu.findItem(R.id.session_mechanic).apply {
            isVisible = state?.phase != "practice"; isEnabled =
            state?.status !in setOf("running", "complete")
        }
        menu.findItem(R.id.session_plan).isVisible = state?.phase != "practice"
        menu.findItem(R.id.session_update_graph).isVisible = state?.phase != "practice"
        menu.findItem(R.id.session_research).apply {
            isChecked = state?.researchAllowed == true; isEnabled = state?.status != "running"
        }
        menu.findItem(R.id.session_update_graph)?.apply {
            setTitle(if (app.extraction.state.value.running) R.string.knowledge_stop else R.string.knowledge_update)
            isEnabled = runtime.state.value?.status != "running"
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.session_mechanic -> {
            val state = runtime.state.value?.takeIf {
                it.id == sessionId && it.status !in setOf(
                    "running",
                    "complete"
                )
            }
            if (state != null) dialog =
                AlertDialog.Builder(activity).setTitle(R.string.runtime_mechanic_note)
                    .setSingleChoiceItems(
                        arrayOf(
                            getString(R.string.runtime_mechanic_choice),
                            getString(R.string.runtime_mechanic_recall)
                        ),
                        if (state.mechanic == "recall") 1 else 0
                    ) { picker, index ->
                        runtime.chooseMechanic(if (index == 1) "recall" else "choice"); picker.dismiss()
                    }.setNegativeButton(android.R.string.cancel, null)
                    .setOnDismissListener { dialog = null }.show()
            true
        }
        R.id.practice_dismiss -> {
            dialog = AlertDialog.Builder(activity).setTitle(R.string.practice_dismiss)
                .setMessage(R.string.practice_dismiss_warning)
                .setPositiveButton(android.R.string.ok) { _, _ -> runtime.dismissProblem() }
                .setNegativeButton(android.R.string.cancel, null)
                .setOnDismissListener { dialog = null }.show()
            true
        }

        R.id.session_sources -> {
            Nav.go(
                activity,
                ResearchSourcesFragment::class.java,
                Bundle().apply { putString("session", sessionId) }); true
        }

        R.id.session_research -> {
            if (runtime.state.value?.researchAllowed == true) runtime.allowResearch(false)
            else dialog = AlertDialog.Builder(activity).setTitle(R.string.research_enable_warning)
                .setMessage(R.string.research_permission)
                .setPositiveButton(R.string.research_enable) { _, _ -> runtime.allowResearch(true) }
                .setNegativeButton(android.R.string.cancel, null)
                .setOnDismissListener { dialog = null }.show()
            true
        }
        R.id.session_plan -> {
            Nav.go(
                activity,
                SessionPlanFragment::class.java,
                Bundle().apply { putString("session", sessionId) }); true
        }

        R.id.session_update_graph -> {
            if (app.extraction.state.value.running) app.extraction.stop()
            else runtime.state.value?.takeIf { it.id == sessionId && it.status != "running" }
                ?.let { app.extraction.start(it) }
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    override fun wantsCustomNavigationIcon() = true
    override fun getNavigationIconDrawableResource() = R.drawable.ic_menu
    override fun onToolbarNavigationClick() = (activity as MainActivity).openDrawer()

    override fun onCreateContentView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        setTitle(R.string.runtime_session_title)
        val views = inflater.inflate(R.layout.fragment_runtime_session, container, false)
        pageView = views
        markdown = lessonMarkdown(inflater.context)
        adapter = LessonAdapter(markdown)
        val list = views.findViewById<RecyclerView>(R.id.lesson_list)
        val options = views.findViewById<LinearLayout>(R.id.question_options)
        val thinking = views.findViewById<EditText>(R.id.question_thinking)
        val readingThinking = views.findViewById<EditText>(R.id.question_reading_thinking)
        val dontKnow = views.findViewById<CheckBox>(R.id.question_dont_know)
        val composer = views.findViewById<EditText>(R.id.lesson_composer)
        val confidence = views.findViewById<SeekBar>(R.id.question_confidence)
        list.layoutManager = LinearLayoutManager(inflater.context)
        list.itemAnimator = null
        list.adapter = adapter
        fun edit() {
            if (!rendering) runtime.state.value?.takeIf { it.id == sessionId }?.let { state ->
                saveDraft(views, state)
            }
        }
        thinking.onEdit(::edit)
        readingThinking.onEdit(::edit)
        composer.onEdit(::edit)
        dontKnow.setOnCheckedChangeListener { _, checked ->
            if (!rendering && checked) {
                rendering = true
                (0 until options.childCount).forEach {
                    (options.getChildAt(it) as CompoundButton).isChecked = false
                }
                rendering = false
            }
            edit()
        }
        confidence.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) edit()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        fun expand(value: Boolean) {
            runtime.state.value?.let {
                runtime.edit(
                    it.draft,
                    it.selection,
                    it.confidence,
                    value,
                    selectedValues = it.selectedValues
                )
            }
        }
        views.findViewById<Button>(R.id.reveal_question).setOnClickListener { expand(true) }
        views.findViewById<Button>(R.id.collapse_question)
            .setOnClickListener { dismissKeyboard(); expand(false) }
        views.findViewById<Button>(R.id.session_model).setOnClickListener { chooseModel() }
        views.findViewById<Button>(R.id.session_thinking).setOnClickListener { chooseThinking() }
        views.findViewById<Button>(R.id.session_context).setOnClickListener { showContext() }
        views.findViewById<Button>(R.id.composer_action).setOnClickListener {
            dismissKeyboard()
            when (runtime.state.value?.status) {
                "running" -> runtime.stop()
                "route", "plan" -> if (runtime.state.value?.draft.isNullOrBlank()) runtime.approveRoute() else runtime.send()
                "error", "interrupted" -> runtime.retry()
                "question" -> runtime.send()
                else -> {
                    val state = runtime.state.value
                    if (state?.phase == "practice" && state.activeProblem == null && state.draft.isBlank() && !state.practiceReady) runtime.nextProblem()
                    else if (state?.draft.isNullOrBlank()) runtime.retry() else runtime.send()
                }
            }
        }
        return views
    }

    override fun onApplyWindowInsets(insets: WindowInsets) {
        val root = view
        val bottom = root?.paddingBottom ?: 0
        super.onApplyWindowInsets(insets)
        // AppKit receives the final IME inset before the animation's first frame. Keep the current
        // bottom until onProgress supplies the actual frame, instead of jumping to the final layout.
        if (imeAnimations > 0 && root != null)
            root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, bottom)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (Build.VERSION.SDK_INT >= 30) {
            // Follow actual IME frames; AppKit owns the final system-bar/keyboard padding.
            view.setWindowInsetsAnimationCallback(object :
                WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onPrepare(animation: WindowInsetsAnimation) {
                    if (animation.typeMask and WindowInsets.Type.ime() != 0) imeAnimations++
                }

                override fun onEnd(animation: WindowInsetsAnimation) {
                    if (animation.typeMask and WindowInsets.Type.ime() != 0) {
                        imeAnimations = (imeAnimations - 1).coerceAtLeast(0)
                        if (imeAnimations == 0) view.requestApplyInsets()
                    }
                }

                override fun onProgress(
                    insets: WindowInsets,
                    runningAnimations: MutableList<WindowInsetsAnimation>
                ): WindowInsets {
                    view.setPadding(
                        view.paddingLeft, view.paddingTop, view.paddingRight,
                        insets.getInsets(WindowInsets.Type.ime() or WindowInsets.Type.systemBars()).bottom
                    )
                    return insets
                }
            })
        }
    }

    override fun onShown() {
        super.onShown()
        toolbar.navigationContentDescription = getString(R.string.navigation_open)
        val views = pageView ?: return
        val start = arguments!!.getBoolean("start_session")
        arguments!!.remove("start_session") // One explicit start, never replayed by reopening/recreation.
        val opened = runtime.open(sessionId)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { visible ->
            visible.launch {
                var menuStatus: String? = null
                runtime.state.collect {
                    if (it?.id == sessionId) {
                        render(views, it)
                        val menuKey = "${it.status}:${it.researchAllowed}:${it.activeProblem?.id}"
                        if (menuStatus != menuKey) {
                            menuStatus = menuKey; invalidateOptionsMenu()
                        }
                    }
                }
            }
            visible.launch {
                app.extraction.state.collect { status ->
                    views.findViewById<TextView>(R.id.knowledge_status).apply {
                        visibility =
                            if (status.running || (status.session == sessionId && status.phase != "idle")) View.VISIBLE else View.GONE
                        text = activity.extractionStatus(status)
                    }
                    invalidateOptionsMenu()
                }
            }
            if (start) visible.launch {
                opened.join()
                if (runtime.failure.value.isEmpty() && runtime.state.value?.let { it.id == sessionId && it.status == "idle" } == true)
                    runtime.retry()
            }
            visible.launch {
                runtime.failure.collect {
                    if (it.isNotBlank()) views.findViewById<TextView>(
                        R.id.lesson_status
                    ).text = activity.runtimeError(it)
                }
            }
            visible.launch {
                try {
                    app.chatGpt.reload()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) { /* Generation reports unreadable credentials; local lessons remain readable. */
                }
            }
        }
    }

    override fun onHidden() {
        dialog?.dismiss(); dialog = null
        runtime.stop()
        scope?.cancel(); scope = null
        loadingModels = false
        super.onHidden()
    }

    override fun onDestroyView() {
        scope?.cancel(); scope = null
        dialog?.dismiss(); dialog = null
        if (Build.VERSION.SDK_INT >= 30) view?.setWindowInsetsAnimationCallback(null)
        imeAnimations = 0
        pageView?.findViewById<RecyclerView>(R.id.lesson_list)?.adapter = null
        pageView = null
        adapter = LessonAdapter()
        markdown = null
        super.onDestroyView()
    }

    private fun saveDraft(views: View, state: RuntimeSession) {
        val options = views.findViewById<LinearLayout>(R.id.question_options)
        // Framework text restoration can fire before this question's dynamic options are rendered.
        if (state.question != null && options.tag != state.question.callId) return
        val selected = if (state.question == null) emptyList() else
            (0 until options.childCount).filter { (options.getChildAt(it) as CompoundButton).isChecked }
        runtime.edit(
            views.findViewById<EditText>(if (state.question == null) R.id.lesson_composer else R.id.question_thinking).text.toString(),
            selected.singleOrNull() ?: -1,
            views.findViewById<SeekBar>(R.id.question_confidence).progress * 20,
            state.expanded,
            views.findViewById<EditText>(R.id.question_reading_thinking).text.toString(),
            views.findViewById<CheckBox>(R.id.question_dont_know).isChecked,
            selected.mapNotNull { state.question?.values?.getOrNull(it) })
    }

    private fun render(views: View, state: RuntimeSession) = with(views) {
        val lessonList = findViewById<RecyclerView>(R.id.lesson_list)
        val lessonStatus = findViewById<TextView>(R.id.lesson_status)
        val questionPanel = findViewById<View>(R.id.question_panel)
        val revealQuestion = findViewById<Button>(R.id.reveal_question)
        val questionText = findViewById<TextView>(R.id.question_text)
        val questionOptions = findViewById<LinearLayout>(R.id.question_options)
        val questionThinking = findViewById<EditText>(R.id.question_thinking)
        val readingThinking = findViewById<EditText>(R.id.question_reading_thinking)
        val dontKnow = findViewById<CheckBox>(R.id.question_dont_know)
        val questionConfidence = findViewById<SeekBar>(R.id.question_confidence)
        val confidenceLabel = findViewById<TextView>(R.id.confidence_label)
        val lessonComposer = findViewById<EditText>(R.id.lesson_composer)
        val composerAction = findViewById<Button>(R.id.composer_action)
        rendering = true
        try {
            val follow = !lessonList.canScrollVertically(1)
            val requested = arguments?.getLong("message", -1) ?: -1
            val target = state.displayItems.indexOfFirst { it.id == requested }
            val changed = adapter.submit(state.displayItems, state.status == "running")
            if (target >= 0) {
                arguments?.remove("message")
                lessonList.post { lessonList.scrollToPosition(target) }
            } else if (changed && follow) lessonList.post { lessonList.scrollToPosition(adapter.itemCount - 1) }
            val role =
                getString(
                    when (state.phase) {
                        "practice" -> R.string.session_practice; "probe" -> R.string.runtime_prober; else -> R.string.runtime_teacher
                    }
                )
            lessonStatus.text = when (state.status) {
                "running" -> if (state.compacting) getString(R.string.runtime_compacting) else role
                "question" -> getString(R.string.runtime_waiting, role)
                "route" -> getString(R.string.runtime_route)
                "plan" -> getString(R.string.plan_ready)
                "interrupted" -> getString(R.string.runtime_interrupted)
                "error" -> activity.runtimeError(state.error)
                "complete" -> getString(R.string.runtime_complete)
                else -> if (state.phase == "practice") getString(if (state.activeProblem != null) R.string.practice_working else R.string.practice_paused) else getString(
                    R.string.runtime_ready
                )
            }
            val question = state.question?.takeIf { state.status == "question" }
            val expanded = question != null && state.expanded
            if (isLaidOut && ValueAnimator.areAnimatorsEnabled() && (questionPanel.visibility == View.VISIBLE) != expanded) {
                TransitionManager.beginDelayedTransition(this as ViewGroup, TransitionSet().apply {
                    duration = resources.getInteger(R.integer.motion_short_ms).toLong()
                    addTransition(ChangeBounds())
                    addTransition(Fade().addTarget(questionPanel))
                })
            }
            questionPanel.visibility = if (expanded) View.VISIBLE else View.GONE
            revealQuestion.visibility =
                if (question != null && !expanded) View.VISIBLE else View.GONE
            // Keep the transcript visible during probing and IME resize, and keep the answer field in the dock.
            questionThinking.visibility =
                if (question != null && (!question.isCheck || expanded)) View.VISIBLE else View.GONE
            readingThinking.visibility = if (question?.isCheck == true) View.VISIBLE else View.GONE
            readingThinking.restore(state.readingThinking)
            dontKnow.visibility =
                if (question != null && question.type != "ask") View.VISIBLE else View.GONE
            dontKnow.isChecked = state.dontKnow
            revealQuestion.setText(if (question?.isCheck == true) R.string.runtime_check_reveal else R.string.resume_question)
            lessonComposer.visibility =
                if (question == null && state.status in setOf(
                        "idle",
                        "route",
                        "plan"
                    )
                ) View.VISIBLE else View.GONE
            if (question != null) {
                if (questionOptions.tag != question.callId) findViewById<android.widget.ScrollView>(
                    R.id.question_scroll
                ).scrollTo(
                    0,
                    0
                )
                findViewById<TextView>(R.id.question_heading).setText(
                    when (question.type) {
                        "predict" -> R.string.runtime_prediction
                        "check" -> R.string.runtime_check_reveal
                        else -> R.string.runtime_question
                    }
                )
                questionText.markdown(
                    listOf(
                        question.text,
                        question.details
                    ).filter { it.isNotBlank() }.joinToString("\n\n")
                )
                questionOptions.visibility =
                    if (question.choices.isEmpty()) View.GONE else View.VISIBLE
                if (questionOptions.tag != question.callId) {
                    questionOptions.removeAllViews()
                    questionOptions.tag = question.callId
                    question.choices.forEachIndexed { index, label ->
                        val option: CompoundButton = if (question.multiSelect)
                            CheckBox(context, null, 0, R.style.Widget_Harness_MultiChoice)
                        else object : RadioButton(context, null, 0, R.style.Widget_Harness_Choice) {
                            override fun toggle() {
                                // A clarification can be deselected to enter a written Other response.
                                if (question.type == "ask") isChecked =
                                    !isChecked else super.toggle()
                            }
                        }
                        // Share AppKit's ID allocator: platform-generated IDs can collide with stack containers.
                        option.id = (activity as MainActivity).generateViewId()
                        option.layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            topMargin = resources.getDimensionPixelSize(R.dimen.space_8)
                        }
                        option.markdown(
                            listOf(
                                label,
                                question.descriptions.getOrNull(index).orEmpty()
                            ).filter { it.isNotBlank() }.joinToString("\n\n")
                        )
                        option.setOnCheckedChangeListener { _, checked ->
                            if (!rendering) {
                                rendering = true
                                if (checked) {
                                    dontKnow.isChecked = false
                                    if (!question.multiSelect) (0 until questionOptions.childCount).forEach {
                                        (questionOptions.getChildAt(it) as CompoundButton).takeUnless { other -> other === option }?.isChecked =
                                            false
                                    }
                                }
                                rendering = false
                                runtime.state.value?.takeIf { it.id == sessionId && it.question?.callId == question.callId }
                                    ?.let { saveDraft(views, it) }
                            }
                        }
                        questionOptions.addView(option)
                    }
                }
                (0 until questionOptions.childCount).forEach { index ->
                    (questionOptions.getChildAt(index) as CompoundButton).isChecked =
                        !state.dontKnow && question.values[index] in state.selectedValues
                }
                questionThinking.restore(state.draft)
                val showConfidence = question.hasConfidence && !state.dontKnow
                questionConfidence.visibility = if (showConfidence) View.VISIBLE else View.GONE
                confidenceLabel.visibility = if (showConfidence) View.VISIBLE else View.GONE
                questionConfidence.progress = state.confidence / 20
                val confidenceTint =
                    android.content.res.ColorStateList.valueOf(activity.confidenceColor(state.confidence))
                questionConfidence.progressTintList = confidenceTint
                questionConfidence.thumbTintList = confidenceTint
                confidenceLabel.text = getString(R.string.confidence_label, state.confidence)
                questionConfidence.stateDescriptionCompat(
                    getString(
                        R.string.runtime_context_percent,
                        state.confidence
                    )
                )
            } else lessonComposer.restore(state.draft)
            lessonComposer.isEnabled = state.status in setOf("idle", "route", "plan")
            lessonComposer.setHint(
                when {
                    state.status == "route" -> R.string.runtime_route_change_hint
                    state.status == "plan" -> R.string.plan_change_hint
                    state.phase == "practice" && state.activeProblem != null -> R.string.practice_thinking_hint
                    else -> R.string.runtime_composer_hint
                }
            )
            val action = when (state.status) {
                "running" -> R.string.stop_stream
                "route" -> if (state.draft.isBlank()) R.string.runtime_approve else R.string.runtime_send
                "plan" -> if (state.draft.isBlank()) R.string.plan_approve else R.string.plan_send_changes
                "error", "interrupted" -> R.string.runtime_retry
                "question" -> R.string.submit_answer
                else -> if (state.draft.isNotBlank()) R.string.runtime_send else if (state.phase == "practice" && state.activeProblem == null && !state.practiceReady) R.string.practice_next else R.string.runtime_start
            }
            val icon = when {
                state.status == "running" -> R.drawable.ic_stop
                state.status == "question" || (state.status in setOf(
                    "idle",
                    "route",
                    "plan"
                ) && state.draft.isNotBlank()) -> R.drawable.ic_send
                else -> 0
            }
            composerAction.text = if (icon == 0) getString(action) else ""
            composerAction.contentDescription = getString(action)
            composerAction.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
            composerAction.isEnabled =
                state.status != "complete" && !(state.phase == "practice" && state.activeProblem != null && state.status == "idle" && state.draft.isBlank()) && (question == null || question.canAnswer(
                    state.draft,
                    state.selectedValues,
                    state.dontKnow
                ))
            val editable = state.status != "running" && state.status != "complete"
            findViewById<Button>(R.id.session_model).apply {
                text =
                    if (loadingModels) getString(R.string.runtime_models_loading) else app.modelCatalog[state.model]?.name
                        ?: state.model
                contentDescription = getString(R.string.runtime_model) + ": " + text
                isEnabled = editable && !loadingModels
            }
            findViewById<Button>(R.id.session_thinking).apply {
                text = effortLabel(state.effort)
                contentDescription = getString(R.string.runtime_thinking_selected, text)
                isEnabled = editable
            }
            val capacity = app.modelCatalog[state.model]?.window ?: 0
            val percent =
                if (state.contextTokens > 0 && capacity > 0) ((state.contextTokens.toDouble() / capacity) * 100).toInt()
                    .coerceIn(0, 100) else null
            findViewById<ProgressBar>(R.id.context_progress).apply { progress = percent ?: 0 }
            findViewById<Button>(R.id.session_context).apply {
                text = percent?.toString() ?: "?"
                contentDescription = getString(
                    R.string.runtime_context_accessibility,
                    percent?.let { getString(R.string.runtime_context_percent, it) }
                        ?: getString(R.string.runtime_context_unknown))
            }
        } finally {
            rendering = false
        }
    }

    private fun chooseModel(refresh: Boolean = false) {
        val state =
            runtime.state.value?.takeIf { it.id == sessionId && it.status != "running" && it.status != "complete" }
                ?: return
        if (loadingModels || dialog != null) return
        scope?.launch {
            loadingModels = true
            pageView?.let { render(it, state) }
            try {
                val models = app.modelCatalog.available(state.connection, refresh) {
                    if (state.connection == "api") OpenAiProvider(app.credentials).models()
                        .filter { app.modelCatalog[it] != null }
                        .map { it to (app.modelCatalog[it]?.name ?: it) }
                    else app.chatGpt.models(state.connection).map { it.slug to it.name }
                }
                val choices = (listOf(
                    state.model to (app.modelCatalog[state.model]?.name ?: state.model)
                ) + models).distinctBy { it.first }
                if (runtime.state.value?.id != sessionId) return@launch
                dialog = AlertDialog.Builder(activity).setTitle(R.string.runtime_model)
                    .setSingleChoiceItems(
                        choices.map { it.second }.toTypedArray(),
                        choices.indexOfFirst { it.first == state.model }) { picker, index ->
                        val id = choices[index].first
                        val effort =
                            state.effort.takeIf { it in app.modelCatalog[id]?.efforts.orEmpty() }
                                .orEmpty()
                        runtime.configure(id, effort)
                        picker.dismiss()
                    }.setNegativeButton(android.R.string.cancel, null)
                    .setNeutralButton(R.string.chatgpt_refresh_models) { picker, _ ->
                        picker.dismiss(); dialog = null; chooseModel(refresh = true)
                    }.setOnDismissListener { dialog = null }.show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                dialog = AlertDialog.Builder(activity).setTitle(R.string.runtime_model)
                    .setMessage(
                        getString(
                            R.string.runtime_models_error,
                            getString(R.string.runtime_models_note)
                        )
                    )
                    .setPositiveButton(android.R.string.ok, null)
                    .setOnDismissListener { dialog = null }.show()
            } finally {
                loadingModels = false
                runtime.state.value?.takeIf { it.id == sessionId }
                    ?.let { current -> pageView?.let { render(it, current) } }
            }
        }
    }

    private fun chooseThinking() {
        val state =
            runtime.state.value?.takeIf { it.id == sessionId && it.status != "running" && it.status != "complete" }
                ?: return
        val model = app.modelCatalog[state.model]
        val levels = listOf("") + model?.efforts.orEmpty()
        val builder = AlertDialog.Builder(activity).setTitle(R.string.runtime_thinking)
        if (levels.size == 1) builder.setMessage(if (model == null) R.string.runtime_thinking_unknown else R.string.runtime_thinking_unavailable)
            .setPositiveButton(android.R.string.ok, null)
        else builder.setSingleChoiceItems(
            levels.map(::effortLabel).toTypedArray(),
            levels.indexOf(state.effort).coerceAtLeast(0)
        ) { picker, index ->
            runtime.configure(state.model, levels[index]); picker.dismiss()
        }.setNegativeButton(android.R.string.cancel, null)
        dialog = builder.setOnDismissListener { dialog = null }.show()
    }

    private fun showContext() {
        val state = runtime.state.value?.takeIf { it.id == sessionId } ?: return
        scope?.launch {
            val details = try {
                withContext(Dispatchers.IO) {
                    app.sessions.extractionUsage(sessionId) to app.sessions.uncovered(
                        state
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            val capacity = app.modelCatalog[state.model]?.window
            val plan = state.connection != "api"
            val label =
                if (plan) app.chatGpt.state.value.accounts.find { it.id == state.connection }?.label
                    ?: getString(R.string.chatgpt_use_plan)
                else getString(R.string.chatgpt_use_api)
            val message = listOf(
                getString(R.string.runtime_tokens_reported, state.inputTokens, state.outputTokens),
                if (state.contextTokens > 0) getString(
                    R.string.runtime_context_reported,
                    state.contextTokens
                ) else getString(R.string.runtime_context_unreported),
                capacity?.let { getString(R.string.runtime_context_capacity, it) }
                    ?: getString(R.string.runtime_context_capacity_unknown),
                getString(R.string.runtime_context_local_limit),
                state.compaction?.let {
                    getString(
                        R.string.runtime_context_compacted,
                        java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it.at))
                    )
                }.orEmpty(),
                getString(R.string.runtime_context_connection, label),
                details?.let { (usage, _) ->
                    getString(
                        R.string.knowledge_tokens,
                        usage.first,
                        usage.second
                    )
                } ?: getString(R.string.runtime_storage_error),
                details?.second?.let { pending ->
                    getString(
                        R.string.knowledge_pending,
                        pending.size,
                        pending.sumOf { it.tokens })
                }.orEmpty()
            ).filter { it.isNotBlank() }.joinToString("\n")
            val builder =
                AlertDialog.Builder(activity).setTitle(R.string.runtime_context).setMessage(message)
                    .setPositiveButton(android.R.string.ok, null)
            if (plan) builder.setNeutralButton(R.string.chatgpt_manage_usage) { _, _ ->
                ChatGptAuth.openUsage(
                    activity
                )
            }
            dialog = builder.setOnDismissListener { dialog = null }.show()
        }
    }

    private fun effortLabel(effort: String): String = getString(
        when (effort) {
            "none" -> R.string.runtime_thinking_none
            "minimal" -> R.string.runtime_thinking_minimal
            "low" -> R.string.runtime_thinking_low
            "medium" -> R.string.runtime_thinking_medium
            "high" -> R.string.runtime_thinking_high
            "xhigh" -> R.string.runtime_thinking_xhigh
            "max" -> R.string.runtime_thinking_max
            else -> R.string.runtime_thinking_auto
        }
    )

    private fun TextView.markdown(value: String) {
        if (tag != value) {
            tag = value; markdown!!.setToolMarkdown(this, value)
        }
    }

    private fun View.stateDescriptionCompat(value: String) {
        if (Build.VERSION.SDK_INT >= 30) stateDescription = value
    }

    private fun dismissKeyboard() {
        pageView?.let { views ->
            views.context.getSystemService(InputMethodManager::class.java)
                .hideSoftInputFromWindow(views.windowToken, 0)
            views.requestFocus()
        }
    }

    private fun EditText.restore(value: String) {
        if (text.toString() != value) setText(value)
    }

    private fun EditText.onEdit(action: () -> Unit) {
        addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = action()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) =
                Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
    }
}
