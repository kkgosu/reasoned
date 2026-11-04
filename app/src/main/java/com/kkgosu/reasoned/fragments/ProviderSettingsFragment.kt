package com.kkgosu.reasoned.fragments

import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import com.kkgosu.reasoned.R
import com.kkgosu.reasoned.ReasonedApplication
import com.kkgosu.reasoned.api.ChatGptAuth
import com.kkgosu.reasoned.api.Connections
import com.kkgosu.reasoned.api.ProviderFailure
import com.kkgosu.reasoned.api.Providers
import com.kkgosu.reasoned.api.WireProvider
import com.kkgosu.reasoned.model.ChatGptStatus
import com.kkgosu.reasoned.ui.ChoiceBottomSheet
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
import me.grishka.appkit.fragments.ToolbarFragment

class ProviderSettingsFragment : ToolbarFragment(R.layout.fragment_shell) {
    private var pageView: View? = null
    private var scope: CoroutineScope? = null
    private var dialog: Dialog? = null
    private var modelsJob: Job? = null
    private var pendingPlan = false
    private lateinit var app: ReasonedApplication
    private val preferences get() = app.providerPreferences
    private var connection = Connections.key("openai")

    override fun onCreateContentView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        app = inflater.context.applicationContext as ReasonedApplication
        setTitle(R.string.settings_providers)
        val views = inflater.inflate(R.layout.fragment_runtime_setup, container, false)
        pageView = views
        val key = views.findViewById<EditText>(R.id.runtime_key)
        val customUrl = views.findViewById<EditText>(R.id.runtime_custom_url)
        val keyless = views.findViewById<CheckBox>(R.id.runtime_custom_keyless)

        views.findViewById<Button>(R.id.runtime_provider).setOnClickListener { chooseProvider() }
        views.findViewById<Button>(R.id.runtime_model).setOnClickListener { chooseModel() }
        views.findViewById<Button>(R.id.runtime_chatgpt_sign_in).setOnClickListener {
            if (app.chatGpt.state.value.busy) app.chatGpt.cancelSignIn()
            else app.chatGpt.signIn(app.chatGpt.state.value.active.ifBlank { null })
        }
        views.findViewById<Button>(R.id.runtime_chatgpt_add).setOnClickListener {
            pendingPlan = true
            app.chatGpt.signIn()
        }
        views.findViewById<Button>(R.id.runtime_chatgpt_account).setOnClickListener {
            val accounts = app.chatGpt.state.value.accounts
            if (accounts.isNotEmpty()) dialog =
                AlertDialog.Builder(views.context).setTitle(R.string.chatgpt_accounts)
                    .setView(TextListAdapter(accounts.map { it.label }, onClick = { index ->
                        dialog?.dismiss()
                        chooseConnection(accounts[index].id)
                    }).createView(views.context))
                    .setOnDismissListener { dialog = null }.show()
        }
        views.findViewById<Button>(R.id.runtime_chatgpt_usage)
            .setOnClickListener { ChatGptAuth.openUsage(activity) }
        views.findViewById<Button>(R.id.chatgpt_sign_out).setOnClickListener {
            action {
                app.runtime.stop().join(); app.extraction.stop()?.join()
                val account = app.chatGpt.state.value.active
                val confirmed = app.chatGpt.signOut(account)
                app.modelCatalog.forget(account)
                status(if (confirmed) R.string.chatgpt_signed_out else R.string.chatgpt_revocation_unconfirmed)
            }
        }
        views.findViewById<Button>(R.id.chatgpt_reset).setOnClickListener {
            dialog = AlertDialog.Builder(views.context).setTitle(R.string.chatgpt_reset)
                .setMessage(R.string.chatgpt_reset_warning)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.chatgpt_reset) { _, _ ->
                    action {
                        app.runtime.stop().join(); app.extraction.stop()?.join()
                        val connections = app.chatGpt.state.value.accounts.map { it.id }
                        app.chatGpt.resetLocal()
                        connections.forEach(app.modelCatalog::forget)
                        status(R.string.chatgpt_revocation_unconfirmed)
                    }
                }.setOnDismissListener { dialog = null }.show()
        }
        views.findViewById<Button>(R.id.runtime_save_key).setOnClickListener {
            val value = key.text.toString().trim()
            val config = runCatching { app.providerConfig(connection) }
            if (config.isFailure) {
                views.findViewById<TextView>(R.id.runtime_setup_status).setText(
                    views.context.runtimeError(
                        (config.exceptionOrNull() as? ProviderFailure)?.reason ?: "provider_unavailable"
                    )
                )
                return@setOnClickListener
            }
            val provider = config.getOrNull()
            if (provider == null || Connections.isPlan(connection)) return@setOnClickListener
            if (value.isEmpty() && provider.id != Connections.CUSTOM) {
                key.error = key.context.getString(R.string.runtime_key_invalid); return@setOnClickListener
            }
            if (value.isNotEmpty() && (value.length !in 16..512 || value.any { it.code !in 33..126 })) {
                key.error = key.context.getString(R.string.runtime_key_invalid); return@setOnClickListener
            }
            action {
                app.extraction.stop()?.join()
                withContext(Dispatchers.IO) {
                    val store = app.credentialStore(provider.credentialId)
                    if (value.isEmpty()) store.forget() else store.save(value)
                    app.modelCatalog.forget(connection)
                }
                key.text.clear()
                status(if (value.isEmpty()) R.string.runtime_key_absent else R.string.runtime_key_saved)
                render(views)
            }
        }
        views.findViewById<Button>(R.id.runtime_forget_key).setOnClickListener {
            val provider = runCatching { app.providerConfig(connection) }.getOrNull()
                ?: return@setOnClickListener
            action {
                app.runtime.stop().join(); app.extraction.stop()?.join()
                withContext(Dispatchers.IO) {
                    app.credentialStore(provider.credentialId).forget()
                    app.modelCatalog.forget(connection)
                }
                key.text.clear()
                status(R.string.runtime_key_absent)
                render(views)
            }
        }
        views.findViewById<Button>(R.id.runtime_custom_save).setOnClickListener {
            val url = customUrl.text.toString().trim().trimEnd('/')
            if (!Connections.customUrl(url)) {
                customUrl.error = customUrl.context.getString(R.string.provider_custom_invalid); return@setOnClickListener
            }
            val previous = runCatching { app.providerConfig(connection)?.baseUrl }.getOrNull()
            action {
                val next = withContext(Dispatchers.IO) { app.saveCustomEndpoint(url, keyless.isChecked) }
                if (previous != null && Connections.endpointId(previous) != Connections.endpointId(url)) {
                    // The field belongs to the previous endpoint; never save it for the new one.
                    key.text.clear()
                }
                customUrl.clearFocus()
                // A key typed here belongs to this newly saved endpoint, so keep it.
                chooseConnection(next, clearKey = false)
                status(R.string.provider_custom_saved)
                render(views)
            }
        }
        val searchKey = views.findViewById<EditText>(R.id.research_key)
        views.findViewById<Button>(R.id.research_save_key).setOnClickListener {
            val value = searchKey.text.toString().trim()
            if (value.length !in 16..512 || value.any { it.code !in 33..126 }) searchKey.error =
                searchKey.context.getString(R.string.runtime_key_invalid)
            else action {
                app.runtime.stop().join()
                withContext(Dispatchers.IO) { app.searchCredentials.save(value) }
                searchKey.text.clear()
                status(R.string.research_key_saved)
                render(views)
            }
        }
        views.findViewById<Button>(R.id.research_forget_key).setOnClickListener {
            action {
                app.runtime.stop().join()
                withContext(Dispatchers.IO) { app.searchCredentials.forget() }
                searchKey.text.clear()
                status(R.string.research_key_absent)
                render(views)
            }
        }
        connection = app.savedConnection()
        render(views)
        return views
    }

    /** ChatGPT plan needs a real account id, so an empty account list starts sign-in first. */
    private fun chooseConnection(next: String, clearKey: Boolean = true) {
        if (Connections.isPlan(next) && next.isBlank()) return
        pendingPlan = false
        connection = next
        // A key typed for the previous provider must never be submitted for the next one. Saving
        // an endpoint that keeps the same server is not a provider switch, so its typed key stays.
        if (clearKey) pageView?.findViewById<EditText>(R.id.runtime_key)?.text?.clear()
        pageView?.findViewById<EditText>(R.id.runtime_custom_url)?.apply {
            text.clear(); clearFocus()
        }
        if (Connections.isPlan(next)) {
            scope?.launch {
                try {
                    // Choosing an account must change the account that will be charged.
                    app.chatGpt.select(next)
                    // Do not overwrite a provider chosen while the account switch was in flight.
                    if (connection == next) app.saveConnection(next)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    status(R.string.runtime_storage_error)
                    return@launch
                }
                if (connection == next) pageView?.let(::render)
            }
        } else {
            app.saveConnection(next)
            pageView?.let(::render)
        }
    }

    private fun chooseProvider() {
        val accounts = app.chatGpt.state.value.accounts
        val entries = mutableListOf<Pair<String, String>>()
        entries += (accounts.firstOrNull { it.id == app.chatGpt.state.value.active }?.id
            ?: accounts.firstOrNull()?.id).orEmpty() to content.context.getString(R.string.chatgpt_use_plan)
        Providers.all().forEach { entries += Connections.key(it.id) to it.label }
        entries += (app.customConnection() ?: Connections.key(Connections.CUSTOM)) to
                content.context.getString(R.string.provider_custom)
        dialog = ChoiceBottomSheet(
            content.context, R.string.provider_choose, entries.map { it.second },
            entries.indexOfFirst { it.first.isNotEmpty() && it.first == connection },
            onConfirm = { index ->
                val picked = entries[index].first
                if (picked.isEmpty()) {
                    pendingPlan = true
                    app.chatGpt.signIn(app.chatGpt.state.value.active.ifBlank { null })
                } else chooseConnection(picked)
            }
        ).apply {
            setOnDismissListener { if (dialog === it) dialog = null }
            show()
        }
    }

    private fun chooseModel(refresh: Boolean = false) {
        if (modelsJob?.isActive == true) return
        val views = pageView ?: return
        val target = connection
        modelsJob = scope?.launch {
            val selected = app.savedModel(target)
            try {
                val models = app.models(target, refresh)
                if (pageView !== views || connection != target) return@launch
                dialog = ChoiceBottomSheet(
                    views.context, R.string.runtime_model, models.map { it.second },
                    models.indexOfFirst { it.first == selected },
                    onConfirm = { index -> saveModel(target, models[index].first) },
                    onRefresh = { chooseModel(refresh = true) },
                    onManual = { manualModel() },
                    message = if (models.isEmpty()) R.string.provider_model_empty else 0
                ).apply {
                    setOnDismissListener { if (dialog === it) dialog = null }
                    show()
                }
                status(if (models.isEmpty()) R.string.provider_model_empty else 0)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (pageView === views && connection == target) manualModel(error)
            } finally {
                if (pageView === views && connection == target) render(views)
            }
        }
    }

    private fun manualModel(error: Exception? = null) {
        val input = EditText(content.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            hint = context.getString(R.string.runtime_model_hint)
            setText(app.savedModel(connection))
        }
        val builder = AlertDialog.Builder(input.context).setTitle(R.string.provider_model_manual)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val value = input.text.toString().trim()
                if (!WireProvider.MODEL_ID.matches(value) || value.startsWith("sk-"))
                    status(R.string.runtime_model_invalid)
                else saveModel(connection, value)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { dialog = null }
        if (error != null) builder.setMessage(
            input.context.getString(
                R.string.runtime_models_error,
                input.context.runtimeError((error as? ProviderFailure)?.reason ?: "provider_unavailable")
            )
        )
        dialog = builder.show()
    }

    private fun saveModel(target: String, model: String) {
        scope?.launch {
            try {
                withContext(Dispatchers.IO) { app.saveConnection(target, model) }
                if (connection == target) {
                    status(R.string.settings_saved)
                    pageView?.let(::render)
                }
            } catch (_: Exception) {
                status(R.string.runtime_storage_error)
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
                status(
                    R.string.runtime_storage_error
                )
            }
        }
    }

    private fun status(resource: Int) {
        pageView?.findViewById<TextView>(R.id.runtime_setup_status)?.apply {
            text = if (resource == 0) "" else context.getText(resource)
        }
    }

    private fun render(views: View) {
        val state = app.chatGpt.state.value
        val usePlan = Connections.isPlan(connection)
        val provider = runCatching { app.providerConfig(connection) }.getOrNull()
        views.findViewById<Button>(R.id.runtime_provider).text =
            views.context.getString(R.string.provider_selected, app.providerLabel(connection))
        val docs = provider?.docs
        views.findViewById<TextView>(R.id.runtime_provider_docs).apply {
            visibility = if (usePlan || docs == null) View.GONE else View.VISIBLE
            if (docs != null) setOnClickListener {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(docs))) }
            }
        }
        views.findViewById<View>(R.id.runtime_chatgpt_fields).visibility =
            if (usePlan) View.VISIBLE else View.GONE
        views.findViewById<View>(R.id.runtime_chatgpt_actions).visibility =
            if (usePlan) View.VISIBLE else View.GONE
        views.findViewById<View>(R.id.runtime_api_fields).visibility =
            if (usePlan) View.GONE else View.VISIBLE
        val custom = Connections.providerId(connection) == Connections.CUSTOM
        views.findViewById<View>(R.id.runtime_custom_fields).visibility =
            if (custom) View.VISIBLE else View.GONE
        views.findViewById<TextView>(R.id.runtime_key_label).text =
            views.context.getString(R.string.provider_key_label, app.providerLabel(connection))
        views.findViewById<TextView>(R.id.runtime_key_note).setText(
            if (custom && provider?.keyless == true) R.string.provider_key_optional
            else R.string.provider_key_note
        )
        val url = views.findViewById<EditText>(R.id.runtime_custom_url)
        if (!url.hasFocus()) url.setText(provider?.baseUrl.orEmpty())
        views.findViewById<CheckBox>(R.id.runtime_custom_keyless).isChecked = provider?.keyless == true
        val model = app.savedModel(connection)
        views.findViewById<Button>(R.id.runtime_model).text = when {
            model.isEmpty() -> views.context.getString(R.string.provider_model_none)
            else -> app.modelCatalog.model(connection, model)?.name ?: model
        }
        if (usePlan) renderPlan(views, state)
    }

    private fun renderPlan(views: View, state: ChatGptStatus) {
        val active = state.accounts.find { it.id == state.active } ?: state.accounts.firstOrNull()
        // Only an unset or removed account follows the account file. A chosen plan account stays
        // chosen until select() publishes it, so the screen never reverts to the old active one.
        if (active != null && (connection.isBlank() || state.accounts.none { it.id == connection })) {
            connection = active.id
            app.saveConnection(active.id)
        }
        views.findViewById<TextView>(R.id.runtime_chatgpt_status).text = when {
            state.busy -> views.context.getString(R.string.chatgpt_browser_pending)
            state.error.isNotEmpty() -> views.context.runtimeError(state.error)
            active?.connected == true && active.plan -> views.context.getString(R.string.chatgpt_connected, active.label)
            active?.connected == true -> views.context.getString(R.string.chatgpt_permission_missing, active.label)
            else -> views.context.getString(R.string.chatgpt_disconnected)
        }
        val account = views.findViewById<Button>(R.id.runtime_chatgpt_account)
        account.text = active?.label ?: account.context.getString(R.string.chatgpt_accounts)
        account.visibility = if (state.accounts.isNotEmpty()) View.VISIBLE else View.GONE
        account.isEnabled = !state.busy
        views.findViewById<Button>(R.id.runtime_chatgpt_add).visibility = account.visibility
        views.findViewById<Button>(R.id.runtime_chatgpt_add).isEnabled = !state.busy
        views.findViewById<Button>(R.id.runtime_chatgpt_sign_in)
            .setText(if (state.busy) R.string.chatgpt_cancel else R.string.chatgpt_continue)
        views.findViewById<Button>(R.id.chatgpt_sign_out).isEnabled =
            active?.connected == true && !state.busy
    }

    override fun onShown() {
        super.onShown()
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val views = pageView ?: return
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        connection = app.savedConnection()
        scope?.launch {
            app.chatGpt.state.collect { state ->
                if (pendingPlan && !state.busy) {
                    pendingPlan = false
                    if (state.error.isEmpty() && Connections.isPlan(state.active)) chooseConnection(state.active)
                }
                render(views)
                if (dialog == null && Connections.isPlan(connection) && state.active.isNotEmpty()) {
                    try {
                        if (app.chatGpt.needsWelcome(state.active)) dialog =
                            AlertDialog.Builder(views.context)
                                .setTitle(R.string.chatgpt_welcome_title)
                                .setMessage(R.string.chatgpt_welcome)
                                .setPositiveButton(R.string.chatgpt_got_it) { _, _ ->
                                    action { app.chatGpt.acknowledgeWelcome(state.active) }
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
            render(views)
            status(
                if (withContext(Dispatchers.IO) {
                        runCatching { app.providerConfig(connection) }.getOrNull()
                            ?.let { app.credentialStore(it.credentialId).hasSavedKey() } == true
                    }) R.string.runtime_key_saved else R.string.runtime_key_absent
            )
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
