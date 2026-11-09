package com.kkgosu.reasoned.fragments

import android.app.AlertDialog
import android.content.Intent
import com.kkgosu.reasoned.model.LessonFile
import com.kkgosu.reasoned.ui.LessonFilePicker
import org.json.JSONArray
import java.util.UUID
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import com.kkgosu.reasoned.MainActivity
import com.kkgosu.reasoned.R
import com.kkgosu.reasoned.ReasonedApplication
import com.kkgosu.reasoned.api.Connections
import com.kkgosu.reasoned.api.ProviderFailure
import com.kkgosu.reasoned.api.WireProvider
import com.kkgosu.reasoned.storage.knowledge
import com.kkgosu.reasoned.ui.adapters.TextListAdapter
import com.kkgosu.reasoned.ui.text.runtimeError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.grishka.appkit.Nav
import me.grishka.appkit.fragments.ToolbarFragment

class NewSessionFragment : ToolbarFragment(R.layout.fragment_shell) {
    private var pageView: View? = null
    private var scope: CoroutineScope? = null
    private val app get() = activity.application as ReasonedApplication
    private val preferences get() = app.getSharedPreferences("provider", 0)
    private var connection = Connections.key("openai")
    private var model = ""
    private var connectionReady = false
    private val targets = linkedSetOf<String>()
    private var dialog: AlertDialog? = null
    private var filePicker: LessonFilePicker? = null
    private val attachmentBack = Runnable { filePicker?.closeMenu() }
    private var fileOwner = UUID.randomUUID().toString()
    private var files = emptyList<LessonFile>()
    private var creation: Job? = null

    override fun wantsCustomNavigationIcon() = true
    override fun getNavigationIconDrawableResource() = R.drawable.ic_menu
    override fun onToolbarNavigationClick() = (activity as MainActivity).openDrawer()

    override fun onCreateContentView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        setTitle(R.string.new_session)
        savedInstanceState?.getString("fileOwner")?.let { fileOwner = it }
        savedInstanceState?.getString("files")?.let { files = LessonFile.parse(JSONArray(it)) }
        val views = inflater.inflate(R.layout.fragment_new_session, container, false)
        pageView = views
        val goal = views.findViewById<EditText>(R.id.runtime_goal)
        val start = views.findViewById<Button>(R.id.runtime_start)
        val status = views.findViewById<TextView>(R.id.session_create_status)
        bindFiles(views)
        val destination = app.providerLabel(app.savedConnection())
        views.findViewById<TextView>(R.id.session_local_notice)
            .text = views.context.getString(R.string.new_session_local, destination)
        views.findViewById<TextView>(R.id.session_research_notice)
            .text = views.context.getString(R.string.new_session_research_notice, destination)
        targets.clear()
        targets.addAll(
            savedInstanceState?.getStringArrayList("targets") ?: arguments?.getString("target")
                ?.let { arrayListOf(it) }.orEmpty()
        )
        if (savedInstanceState == null) arguments?.getString("topic")?.let(goal::setText)
        val mechanic = views.findViewById<RadioGroup>(R.id.session_mechanic)
        if (savedInstanceState == null) mechanic.check(
            if (preferences.getString(
                    "mechanic",
                    "choice"
                ) == "recall"
            ) R.id.mechanic_recall else R.id.mechanic_choice
        )
        val mode = views.findViewById<RadioGroup>(R.id.session_mode)
        if (savedInstanceState == null && arguments?.getString("target") != null) mode.check(R.id.session_practice)
        fun modeChanged() {
            val practice = mode.checkedRadioButtonId == R.id.session_practice
            views.findViewById<View>(R.id.session_mechanic_section).visibility =
                if (practice) View.GONE else View.VISIBLE
            views.findViewById<View>(R.id.practice_targets).visibility =
                if (practice) View.VISIBLE else View.GONE
            views.findViewById<View>(R.id.practice_targets_note).visibility =
                if (practice) View.VISIBLE else View.GONE
            views.findViewById<Button>(R.id.practice_targets).text =
                views.context.getString(R.string.practice_targets, targets.size)
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
                dialog = AlertDialog.Builder(views.context).setTitle(R.string.practice_choose)
                    .setView(TextListAdapter(
                        nodes.map { it.name + "\n" + it.statement.take(120) },
                        R.layout.item_list_multiple_choice,
                        isChecked = { nodes[it].id in selected },
                        onClick = { index ->
                            val id = nodes[index].id
                            if (id in selected) selected -= id
                            else if (selected.size >= 8) status.setText(R.string.practice_limit)
                            else selected += id
                        }
                    ).createView(views.context)).setPositiveButton(android.R.string.ok) { _, _ ->
                        targets.clear(); targets.addAll(selected); modeChanged()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .setOnDismissListener { dialog = null }.show()
            }
        }
        views.findViewById<Button>(R.id.session_settings)
            .setOnClickListener { Nav.go(activity, ProviderSettingsFragment::class.java, null) }
        start.setOnClickListener {
            if (!connectionReady || filePicker?.busy == true || creation?.isActive == true) return@setOnClickListener
            val text = goal.text.toString().trim()
            if (text.isBlank()) {
                goal.error = goal.context.getString(R.string.runtime_goal_invalid); return@setOnClickListener
            }
            start.isEnabled = false
            filePicker?.render(false)
            scope?.launch {
                try {
                    creation = app.runtime.create(
                        text,
                        model,
                        connection,
                        practice = mode.checkedRadioButtonId == R.id.session_practice,
                        targets = if (mode.checkedRadioButtonId == R.id.session_practice) targets.toList() else emptyList(),
                        researchAllowed = views.findViewById<CheckBox>(R.id.session_research).isChecked,
                        mechanic = if (mechanic.checkedRadioButtonId == R.id.mechanic_recall) "recall" else "choice",
                        files = files, id = fileOwner
                    )
                    creation!!.join()
                    if (app.runtime.failure.value.isEmpty()) app.runtime.state.value?.let {
                        preferences.edit().putString("mechanic", it.mechanic).apply()
                        fileOwner = UUID.randomUUID().toString(); files = emptyList(); bindFiles(views)
                        (activity as MainActivity).openSession(
                            it.id,
                            start = true
                        )
                    }
                    else status.text = status.context.runtimeError(app.runtime.failure.value)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    status.text =
                        status.context.runtimeError((error as? ProviderFailure)?.reason ?: "storage")
                } finally {
                    filePicker?.render(creation?.isActive != true)
                    start.isEnabled = connectionReady && filePicker?.busy != true && creation?.isActive != true
                }
            }
        }
        return views
    }

    override fun onShown() {
        super.onShown()
        toolbar.navigationContentDescription = toolbar.context.getString(R.string.navigation_open)
        val views = pageView ?: return
        val start = views.findViewById<Button>(R.id.runtime_start)
        connectionReady = false; start.isEnabled = false
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope?.launch {
            try {
                creation?.join()
                if (withContext(Dispatchers.IO) { app.sessions.load(fileOwner) != null }) {
                    fileOwner = UUID.randomUUID().toString(); files = emptyList(); bindFiles(views)
                }
                filePicker?.render(true)
                filePicker?.onShown()
                val usePlan = Connections.isPlan(app.savedConnection())
                if (usePlan) app.chatGpt.reload()
                connection = app.savedConnection()
                model = app.savedModel(connection)
                val account = app.chatGpt.state.value.accounts.find { it.id == connection }
                connectionReady =
                    WireProvider.MODEL_ID.matches(model) && !model.startsWith("sk-") &&
                            (!usePlan || account?.let { it.connected && it.plan } == true)
                views.findViewById<TextView>(R.id.session_connection).text = if (connectionReady) views.context.getString(
                    R.string.new_session_connection,
                    if (usePlan) account!!.label else app.providerLabel(connection), model
                ) else views.context.getString(R.string.new_session_configure)
                val destination =
                    if (usePlan) account?.label ?: views.context.getString(R.string.chatgpt_use_plan)
                    else app.providerLabel(connection)
                views.findViewById<TextView>(R.id.session_local_notice)
                    .text = views.context.getString(R.string.new_session_local, destination)
                views.findViewById<TextView>(R.id.session_research_notice)
                    .text = views.context.getString(R.string.new_session_research_notice, destination)
                start.isEnabled = connectionReady && filePicker?.busy != true && creation?.isActive != true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                start.isEnabled = false
                views.findViewById<TextView>(R.id.session_create_status)
                    .setText(R.string.runtime_storage_error)
            }
        }
    }

    private fun bindFiles(views: View) {
        filePicker = LessonFilePicker(this, views as ViewGroup, fileOwner,
            { scope }, { files }, { if (creation?.isActive == true) false else { files = it; true } }, {
                removeBackCallback(attachmentBack)
                if (filePicker?.menuOpen == true) addBackCallback(attachmentBack)
                views.findViewById<Button>(R.id.runtime_start).isEnabled = connectionReady && filePicker?.busy != true && creation?.isActive != true
            }).also { it.render(creation?.isActive != true) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (filePicker?.onActivityResult(requestCode, resultCode, data) != true) super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("fileOwner", fileOwner)
        outState.putString("files", JSONArray(files.map { it.json() }).toString())
        super.onSaveInstanceState(outState); outState.putStringArrayList(
            "targets",
            ArrayList(targets)
        )
    }

    override fun onHidden() {
        connectionReady = false
        pageView?.findViewById<Button>(R.id.runtime_start)?.isEnabled = false
        filePicker?.closeMenu()
        dialog?.dismiss(); dialog = null
        scope?.cancel(); scope = null; super.onHidden()
    }

    override fun onDestroyView() {
        connectionReady = false
        filePicker?.closeMenu()
        removeBackCallback(attachmentBack)
        dialog?.dismiss(); dialog = null
        scope?.cancel(); scope = null; pageView = null; filePicker = null; super.onDestroyView()
    }
}
