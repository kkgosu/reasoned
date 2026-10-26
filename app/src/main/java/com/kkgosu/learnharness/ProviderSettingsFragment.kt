package com.kkgosu.learnharness

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.grishka.appkit.fragments.ToolbarFragment

class ProviderSettingsFragment : ToolbarFragment(R.layout.fragment_shell) {
    private var pageView: View? = null
    private var scope: CoroutineScope? = null
    private var dialog: AlertDialog? = null
    private var modelConnection: String? = null
    private var modelsJob: Job? = null
    private lateinit var app: ReasonedApplication
    private val preferences get() = app.getSharedPreferences("provider", 0)

    override fun onCreateContentView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        app = inflater.context.applicationContext as ReasonedApplication
        setTitle(R.string.settings_providers)
        val views = inflater.inflate(R.layout.fragment_runtime_setup, container, false)
        pageView = views
        val billing = views.findViewById<RadioGroup>(R.id.runtime_billing)
        val model = views.findViewById<EditText>(R.id.runtime_model)
        val key = views.findViewById<EditText>(R.id.runtime_key)
        billing.check(
            if (preferences.getBoolean(
                    "chatgpt",
                    false
                )
            ) R.id.runtime_use_chatgpt else R.id.runtime_use_api
        )
        billing.setOnCheckedChangeListener { _, checked ->
            preferences.edit().putBoolean("chatgpt", checked == R.id.runtime_use_chatgpt).apply()
            render(views, app.chatGpt.state.value)
        }
        views.findViewById<Button>(R.id.runtime_chatgpt_sign_in).setOnClickListener {
            if (app.chatGpt.state.value.busy) app.chatGpt.cancelSignIn()
            else app.chatGpt.signIn(app.chatGpt.state.value.active.ifBlank { null })
        }
        views.findViewById<Button>(R.id.runtime_chatgpt_add)
            .setOnClickListener { app.chatGpt.signIn() }
        views.findViewById<Button>(R.id.runtime_chatgpt_account).setOnClickListener {
            val accounts = app.chatGpt.state.value.accounts
            if (accounts.isNotEmpty()) dialog =
                AlertDialog.Builder(activity).setTitle(R.string.chatgpt_accounts)
                    .setItems(accounts.map { it.label }
                        .toTypedArray()) { _, index -> action { app.chatGpt.select(accounts[index].id) } }
                    .setOnDismissListener { dialog = null }.show()
        }
        views.findViewById<Button>(R.id.chatgpt_sign_out).setOnClickListener {
            action {
                app.runtime.stop().join(); app.extraction.stop()?.join()
                val connection = app.chatGpt.state.value.active
                val confirmed = app.chatGpt.signOut(connection)
                app.modelCatalog.forget(connection)
                pageView?.findViewById<TextView>(R.id.runtime_setup_status)
                    ?.setText(if (confirmed) R.string.chatgpt_signed_out else R.string.chatgpt_revocation_unconfirmed)
            }
        }
        views.findViewById<Button>(R.id.chatgpt_reset).setOnClickListener {
            dialog = AlertDialog.Builder(activity).setTitle(R.string.chatgpt_reset)
                .setMessage(R.string.chatgpt_reset_warning)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.chatgpt_reset) { _, _ ->
                    action {
                        app.runtime.stop().join(); app.extraction.stop()?.join()
                        val connections = app.chatGpt.state.value.accounts.map { it.id }
                        app.chatGpt.resetLocal()
                        connections.forEach(app.modelCatalog::forget)
                        pageView?.findViewById<TextView>(R.id.runtime_setup_status)
                            ?.setText(R.string.chatgpt_revocation_unconfirmed)
                    }
                }.setOnDismissListener { dialog = null }.show()
        }
        views.findViewById<Button>(R.id.runtime_chatgpt_usage)
            .setOnClickListener { ChatGptAuth.openUsage(activity) }
        views.findViewById<Button>(R.id.runtime_chatgpt_models)
            .setOnClickListener { loadModels(refresh = true) }
        views.findViewById<Button>(R.id.runtime_save_model).setOnClickListener {
            val value = model.text.toString().trim()
            if (!value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")) || value.startsWith("sk-"))
                model.error = getString(R.string.runtime_model_invalid)
            else saveModel("api", value)
        }
        views.findViewById<Button>(R.id.runtime_save_key).setOnClickListener {
            val value = key.text.toString().trim()
            if (value.length !in 16..512 || value.any { it.code !in 33..126 }) key.error =
                getString(R.string.runtime_key_invalid)
            else action {
                app.extraction.stop()?.join()
                withContext(Dispatchers.IO) { app.credentials.save(value); app.modelCatalog.forget("api") }
                key.text.clear()
                views.findViewById<TextView>(R.id.runtime_key_status)
                    .setText(R.string.runtime_key_saved)
            }
        }
        views.findViewById<Button>(R.id.runtime_forget_key).setOnClickListener {
            action {
                app.runtime.stop().join(); app.extraction.stop()?.join()
                withContext(Dispatchers.IO) { app.credentials.forget(); app.modelCatalog.forget("api") }
                key.text.clear()
                views.findViewById<TextView>(R.id.runtime_key_status)
                    .setText(R.string.runtime_key_absent)
            }
        }
        val searchKey = views.findViewById<EditText>(R.id.research_key)
        views.findViewById<Button>(R.id.research_save_key).setOnClickListener {
            val value = searchKey.text.toString().trim()
            if (value.length !in 16..512 || value.any { it.code !in 33..126 }) searchKey.error =
                getString(R.string.runtime_key_invalid)
            else action {
                app.runtime.stop().join()
                withContext(Dispatchers.IO) { app.searchCredentials.save(value) }
                searchKey.text.clear()
                views.findViewById<TextView>(R.id.research_key_status)
                    .setText(R.string.research_key_saved)
            }
        }
        views.findViewById<Button>(R.id.research_forget_key).setOnClickListener {
            action {
                app.runtime.stop().join()
                withContext(Dispatchers.IO) { app.searchCredentials.forget() }
                searchKey.text.clear()
                views.findViewById<TextView>(R.id.research_key_status)
                    .setText(R.string.research_key_absent)
            }
        }
        render(views, app.chatGpt.state.value)
        return views
    }

    private fun saveModel(connection: String, model: String) = action {
        withContext(Dispatchers.IO) {
            check(
                preferences.edit().putString("model:$connection", model).commit()
            )
        }
        if (modelConnection == connection) {
            pageView?.findViewById<EditText>(R.id.runtime_model)?.setText(model)
            pageView?.findViewById<TextView>(R.id.runtime_setup_status)
                ?.setText(R.string.settings_saved)
        }
    }

    private fun loadModels(refresh: Boolean = false) {
        val views = pageView ?: return
        val connection = modelConnection ?: return
        modelsJob?.cancel()
        modelsJob = scope?.launch {
            val refreshButton = views.findViewById<Button>(R.id.runtime_chatgpt_models)
            val status = views.findViewById<TextView>(R.id.runtime_setup_status)
            refreshButton.isEnabled = false
            status.setText(R.string.chatgpt_models_loading)
            try {
                val catalog = app.modelCatalog.available(connection, refresh) {
                    app.chatGpt.models(connection).map { it.slug to it.name }
                }
                if (pageView !== views || modelConnection != connection) return@launch
                val list = views.findViewById<RadioGroup>(R.id.runtime_chatgpt_model_list)
                list.removeAllViews()
                val selected = preferences.getString("model:$connection", "")
                for ((slug, name) in catalog) {
                    list.addView(
                        RadioButton(
                            activity,
                            null,
                            0,
                            R.style.Widget_Harness_BillingChoice
                        ).apply {
                            id = (activity as MainActivity).generateViewId()
                            layoutParams = RadioGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            )
                            isSaveEnabled = false
                            text = name
                            tag = slug
                            isChecked = slug == selected
                            setOnClickListener {
                                if (modelConnection == connection) saveModel(
                                    connection,
                                    slug
                                )
                            }
                        })
                }
                status.text = if (catalog.isEmpty()) getString(R.string.chatgpt_no_models) else ""
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                status.text = activity.runtimeError(
                    (error as? ProviderFailure)?.reason ?: "chatgpt_models_failed"
                )
            } finally {
                if (pageView === views && modelConnection == connection) {
                    val state = app.chatGpt.state.value
                    refreshButton.isEnabled =
                        !state.busy && state.accounts.any { it.id == connection && it.connected && it.plan }
                }
            }
        }
    }

    private fun action(block: suspend () -> Unit) {
        scope?.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                pageView?.findViewById<TextView>(R.id.runtime_setup_status)?.text =
                    activity.runtimeError((error as? ProviderFailure)?.reason ?: "settings_storage")
            }
        }
    }

    private fun render(views: View, state: ChatGptStatus) {
        val usePlan =
            views.findViewById<RadioGroup>(R.id.runtime_billing).checkedRadioButtonId == R.id.runtime_use_chatgpt
        views.findViewById<View>(R.id.runtime_chatgpt_fields).visibility =
            if (usePlan) View.VISIBLE else View.GONE
        views.findViewById<View>(R.id.runtime_api_fields).visibility =
            if (usePlan) View.GONE else View.VISIBLE
        val refresh = views.findViewById<Button>(R.id.runtime_chatgpt_models)
        refresh.visibility = if (usePlan) View.VISIBLE else View.GONE
        views.findViewById<View>(R.id.runtime_chatgpt_actions).visibility =
            if (usePlan) View.VISIBLE else View.GONE
        views.findViewById<View>(R.id.runtime_save_model).visibility =
            if (usePlan) View.GONE else View.VISIBLE
        val model = views.findViewById<EditText>(R.id.runtime_model)
        model.visibility = if (usePlan) View.GONE else View.VISIBLE
        model.isEnabled = !usePlan
        val list = views.findViewById<RadioGroup>(R.id.runtime_chatgpt_model_list)
        list.visibility = if (usePlan) View.VISIBLE else View.GONE
        views.findViewById<TextView>(R.id.runtime_model_label).labelFor =
            if (usePlan) list.id else model.id
        val connection = if (usePlan) state.active else "api"
        if (modelConnection != connection) {
            modelsJob?.cancel(); modelsJob = null
            list.removeAllViews()
            model.setText(
                preferences.getString(
                    "model:$connection",
                    if (usePlan) "" else preferences.getString("model", "")
                )
            )
            views.findViewById<TextView>(R.id.runtime_setup_status).text = ""
            modelConnection = connection
        }
        val active = state.accounts.find { it.id == state.active }
        val ready = usePlan && active?.connected == true && active.plan && !state.busy
        if (!ready) {
            modelsJob?.cancel(); modelsJob = null; list.removeAllViews()
        }
        views.findViewById<TextView>(R.id.runtime_chatgpt_status).text = when {
            state.busy -> getString(R.string.chatgpt_browser_pending)
            state.error.isNotEmpty() -> activity.runtimeError(state.error)
            active?.connected == true && active.plan -> getString(
                R.string.chatgpt_connected,
                active.label
            )

            active?.connected == true -> getString(
                R.string.chatgpt_permission_missing,
                active.label
            )

            else -> getString(R.string.chatgpt_disconnected)
        }
        val account = views.findViewById<Button>(R.id.runtime_chatgpt_account)
        val add = views.findViewById<Button>(R.id.runtime_chatgpt_add)
        account.text = active?.label ?: getString(R.string.chatgpt_accounts)
        account.visibility = if (state.accounts.isNotEmpty()) View.VISIBLE else View.GONE
        add.visibility = account.visibility
        account.isEnabled = !state.busy
        views.findViewById<Button>(R.id.runtime_chatgpt_sign_in)
            .setText(if (state.busy) R.string.chatgpt_cancel else R.string.chatgpt_continue)
        add.isEnabled = !state.busy
        refresh.isEnabled = ready && modelsJob?.isActive != true
        views.findViewById<Button>(R.id.chatgpt_sign_out).isEnabled =
            active?.connected == true && !state.busy
        if (ready && modelsJob == null) loadModels()
    }

    override fun onShown() {
        super.onShown()
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val views = pageView ?: return
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope?.launch {
            app.chatGpt.state.collect { state ->
                render(views, state)
                if (dialog == null && views.findViewById<RadioGroup>(R.id.runtime_billing).checkedRadioButtonId == R.id.runtime_use_chatgpt && state.active.isNotEmpty()) {
                    try {
                        if (app.chatGpt.needsWelcome(state.active)) dialog =
                            AlertDialog.Builder(activity)
                                .setTitle(R.string.chatgpt_welcome_title)
                                .setMessage(R.string.chatgpt_welcome)
                                .setPositiveButton(R.string.chatgpt_got_it) { _, _ ->
                                    action {
                                        app.chatGpt.acknowledgeWelcome(
                                            state.active
                                        )
                                    }
                                }
                                .setOnDismissListener { dialog = null }.show()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        views.findViewById<TextView>(R.id.runtime_setup_status)
                            .setText(R.string.runtime_storage_error)
                    }
                }
            }
        }
        action {
            app.chatGpt.reload()
            views.findViewById<TextView>(R.id.runtime_key_status)
                .setText(if (withContext(Dispatchers.IO) { app.credentials.hasSavedKey() }) R.string.runtime_key_saved else R.string.runtime_key_absent)
            views.findViewById<TextView>(R.id.research_key_status)
                .setText(if (withContext(Dispatchers.IO) { app.searchCredentials.hasSavedKey() }) R.string.research_key_saved else R.string.research_key_absent)
        }
    }

    override fun onHidden() {
        pageView?.findViewById<EditText>(R.id.runtime_key)?.text?.clear()
        pageView?.findViewById<EditText>(R.id.research_key)?.text?.clear()
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog?.dismiss(); dialog = null
        scope?.cancel(); scope = null
        modelsJob = null
        super.onHidden()
    }

    override fun onDestroyView() {
        scope?.cancel(); scope = null
        modelsJob = null
        dialog?.dismiss(); dialog = null
        pageView = null
        super.onDestroyView()
    }
}
