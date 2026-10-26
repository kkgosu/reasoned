package com.kkgosu.learnharness

import android.app.AlertDialog
import android.os.Bundle
import android.widget.CheckBox
import android.widget.RadioGroup
import kotlinx.coroutines.withContext
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.grishka.appkit.Nav
import me.grishka.appkit.fragments.ToolbarFragment

class NewSessionFragment : ToolbarFragment(R.layout.fragment_shell) {
    private var pageView: View? = null
    private var scope: CoroutineScope? = null
    private val app get() = activity.application as ReasonedApplication
    private val preferences get() = app.getSharedPreferences("provider", 0)
    private var connection = "api"
    private var model = ""
    private val targets = linkedSetOf<String>()
    private var dialog: AlertDialog? = null

    override fun wantsCustomNavigationIcon() = true
    override fun getNavigationIconDrawableResource() = R.drawable.ic_menu
    override fun onToolbarNavigationClick() = (activity as MainActivity).openDrawer()

    override fun onCreateContentView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        setTitle(R.string.new_session)
        val views = inflater.inflate(R.layout.fragment_new_session, container, false)
        pageView = views
        val goal = views.findViewById<EditText>(R.id.runtime_goal)
        val start = views.findViewById<Button>(R.id.runtime_start)
        val status = views.findViewById<TextView>(R.id.session_create_status)
        targets.clear()
        targets.addAll(
            savedInstanceState?.getStringArrayList("targets") ?: arguments?.getString("target")
                ?.let { arrayListOf(it) }.orEmpty()
        )
        if (savedInstanceState == null) arguments?.getString("topic")?.let(goal::setText)
        val mode = views.findViewById<RadioGroup>(R.id.session_mode)
        if (savedInstanceState == null && arguments?.getString("target") != null) mode.check(R.id.session_practice)
        fun modeChanged() {
            val practice = mode.checkedRadioButtonId == R.id.session_practice
            views.findViewById<View>(R.id.practice_targets).visibility =
                if (practice) View.VISIBLE else View.GONE
            views.findViewById<View>(R.id.practice_targets_note).visibility =
                if (practice) View.VISIBLE else View.GONE
            views.findViewById<Button>(R.id.practice_targets).text =
                getString(R.string.practice_targets, targets.size)
            views.findViewById<TextView>(R.id.runtime_goal_label)
                .setText(if (practice) R.string.practice_intro else R.string.new_session_intro)
        }
        mode.setOnCheckedChangeListener { _, _ -> modeChanged() }; modeChanged()
        views.findViewById<Button>(R.id.practice_targets).setOnClickListener {
            scope?.launch {
                val nodes = try {
                    withContext(Dispatchers.IO) { app.sessions.knowledge().nodes.take(100) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    status.setText(R.string.runtime_storage_error); return@launch
                }
                if (nodes.isEmpty()) {
                    status.setText(R.string.practice_empty); return@launch
                }
                val selected = targets.toMutableSet()
                dialog = AlertDialog.Builder(activity).setTitle(R.string.practice_choose)
                    .setMultiChoiceItems(
                        nodes.map { it.name + "\n" + it.statement.take(120) }.toTypedArray(),
                        nodes.map { it.id in selected }.toBooleanArray()
                    ) { choice, index, checked ->
                        if (checked && selected.size >= 8) {
                            (choice as AlertDialog).listView.setItemChecked(index, false)
                            status.setText(R.string.practice_limit)
                        } else if (checked) selected += nodes[index].id else selected -= nodes[index].id
                    }.setPositiveButton(android.R.string.ok) { _, _ ->
                        targets.clear(); targets.addAll(selected); modeChanged()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .setOnDismissListener { dialog = null }.show()
            }
        }
        views.findViewById<Button>(R.id.session_settings)
            .setOnClickListener { Nav.go(activity, ProviderSettingsFragment::class.java, null) }
        start.setOnClickListener {
            val text = goal.text.toString().trim()
            if (text.isBlank()) {
                goal.error = getString(R.string.runtime_goal_invalid); return@setOnClickListener
            }
            start.isEnabled = false
            scope?.launch {
                try {
                    app.runtime.create(
                        text,
                        model,
                        connection,
                        practice = mode.checkedRadioButtonId == R.id.session_practice,
                        targets = if (mode.checkedRadioButtonId == R.id.session_practice) targets.toList() else emptyList(),
                        researchAllowed = views.findViewById<CheckBox>(R.id.session_research).isChecked
                    ).join()
                    if (app.runtime.failure.value.isEmpty()) app.runtime.state.value?.let {
                        (activity as MainActivity).openSession(
                            it.id,
                            start = true
                        )
                    }
                    else status.text = activity.runtimeError(app.runtime.failure.value)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    status.text =
                        activity.runtimeError((error as? ProviderFailure)?.reason ?: "storage")
                } finally {
                    start.isEnabled = model.isNotBlank()
                }
            }
        }
        return views
    }

    override fun onShown() {
        super.onShown()
        toolbar.navigationContentDescription = getString(R.string.navigation_open)
        val views = pageView ?: return
        val start = views.findViewById<Button>(R.id.runtime_start)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope?.launch {
            try {
                val usePlan = preferences.getBoolean("chatgpt", false)
                if (usePlan) app.chatGpt.reload()
                connection = if (usePlan) app.chatGpt.state.value.active else "api"
                model = preferences.getString(
                    "model:$connection",
                    if (usePlan) "" else preferences.getString("model", "")
                ).orEmpty()
                val account = app.chatGpt.state.value.accounts.find { it.id == connection }
                val ready =
                    model.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")) && !model.startsWith("sk-") &&
                            (!usePlan || account?.let { it.connected && it.plan } == true)
                views.findViewById<TextView>(R.id.session_connection).text = if (ready) getString(
                    R.string.new_session_connection,
                    if (usePlan) account!!.label else getString(R.string.chatgpt_use_api), model
                ) else getString(R.string.new_session_configure)
                start.isEnabled = ready
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                start.isEnabled = false
                views.findViewById<TextView>(R.id.session_create_status)
                    .setText(R.string.runtime_storage_error)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState); outState.putStringArrayList(
            "targets",
            ArrayList(targets)
        )
    }

    override fun onHidden() {
        dialog?.dismiss(); dialog = null
        scope?.cancel(); scope = null; super.onHidden()
    }

    override fun onDestroyView() {
        dialog?.dismiss(); dialog = null
        scope?.cancel(); scope = null; pageView = null; super.onDestroyView()
    }
}
