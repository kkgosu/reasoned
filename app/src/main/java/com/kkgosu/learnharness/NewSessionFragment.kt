package com.kkgosu.learnharness

import android.os.Bundle
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
                    app.runtime.create(text, model, connection).join()
                    if (app.runtime.failure.value.isEmpty()) app.runtime.state.value?.let {
                        (activity as MainActivity).openSession(
                            it.id,
                            start = true
                        )
                    }
                    else status.setText(R.string.runtime_storage_error)
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

    override fun onHidden() {
        scope?.cancel(); scope = null; super.onHidden()
    }

    override fun onDestroyView() {
        scope?.cancel(); scope = null; pageView = null; super.onDestroyView()
    }
}
