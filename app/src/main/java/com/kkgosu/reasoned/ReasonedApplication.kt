package com.kkgosu.reasoned

import android.app.Application
import android.app.Activity
import com.kkgosu.reasoned.api.LessonLan
import android.os.Bundle
import com.kkgosu.reasoned.controllers.MockLesson
import com.kkgosu.reasoned.api.ChatGptAuth
import com.kkgosu.reasoned.api.Connections
import com.kkgosu.reasoned.api.ModelProvider
import com.kkgosu.reasoned.api.OpenAiProvider
import com.kkgosu.reasoned.api.Provider
import com.kkgosu.reasoned.api.ProviderFailure
import com.kkgosu.reasoned.api.Providers
import com.kkgosu.reasoned.api.ResearchWeb
import com.kkgosu.reasoned.api.Wire
import com.kkgosu.reasoned.api.WireProvider
import com.kkgosu.reasoned.controllers.KnowledgeExtraction
import com.kkgosu.reasoned.controllers.SessionRuntime
import com.kkgosu.reasoned.storage.CredentialStore
import com.kkgosu.reasoned.storage.ModelCatalog
import com.kkgosu.reasoned.storage.SessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ReasonedApplication : Application() {
    internal val lessonLan by lazy { LessonLan(this) }

    override fun onCreate() {
        super.onCreate()
        Providers.load(this)
        // Include the scanner activity; fragment navigation must not interrupt device presence.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(activity: Activity) { if (started++ == 0) lessonLan.setForeground(true) }
            override fun onActivityStopped(activity: Activity) { if (--started == 0) lessonLan.setForeground(false) }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    internal val searchCredentials by lazy { CredentialStore(this, "brave") }
    internal val web by lazy { ResearchWeb(searchCredentials) }
    internal val sessions by lazy { SessionStore(this) }
    internal val modelCatalog by lazy { ModelCatalog(this) }
    internal val chatGpt by lazy { ChatGptAuth(this) }

    /** One encrypted no-backup file per provider; the legacy OpenAI store keeps its name. */
    private val providerStores = mutableMapOf<String, CredentialStore>()

    @Synchronized
    internal fun credentialStore(provider: String): CredentialStore = providerStores.getOrPut(provider) {
        CredentialStore(this, if (provider == "openai") "openai" else "provider-$provider")
    }

    /** The saved provider list plus the single user-configured OpenAI-compatible endpoint. */
    internal fun providerConfig(connection: String): Provider? {
        if (Connections.providerId(connection) != Connections.CUSTOM) return Providers.of(connection)
        val preferences = getSharedPreferences("provider", 0)
        val url = preferences.getString("custom_url", "").orEmpty().trim().trimEnd('/')
        if (!Connections.customUrl(url)) throw ProviderFailure("custom_endpoint_missing")
        return Provider(
            id = Connections.CUSTOM,
            label = getString(R.string.provider_custom),
            wire = Wire.CHAT,
            baseUrl = url,
            docs = null,
            models = emptyList(),
            keyless = preferences.getBoolean("custom_keyless", false),
        )
    }

    private fun provider(connection: String): ModelProvider {
        if (Connections.isPlan(connection)) return OpenAiProvider(
            { chatGpt.accessToken(connection) },
            chatGpt.client
        )
        val config = providerConfig(connection) ?: throw ProviderFailure("provider_unavailable")
        return WireProvider(config, { credentialStore(config.id).read().orEmpty() })
    }

    /** Curated provider models always list; discovery is an explicit refresh and never required. */
    internal suspend fun models(connection: String, refresh: Boolean = false): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            if (Connections.isPlan(connection)) return@withContext modelCatalog.available(connection, refresh) {
                chatGpt.models(connection).map { it.slug to it.name }
            }
            val provider = providerConfig(connection) ?: throw ProviderFailure("provider_unavailable")
            val wire = WireProvider(provider, { credentialStore(provider.id).read().orEmpty() })
            if (provider.id == Connections.CUSTOM) return@withContext wire.models()
            val curated = provider.models.map { it.id to it.name }
            if (!refresh) return@withContext curated
            val discovered = try {
                wire.models()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            (curated + discovered).distinctBy { it.first }
        }

    internal val providerPreferences: android.content.SharedPreferences
        get() = getSharedPreferences("provider", 0)

    /** The saved default connection. Legacy `api`/`chatgpt` preferences resolve to current ids. */
    internal fun savedConnection(): String {
        val preferences = providerPreferences
        val saved = preferences.getString("connection", null)
        if (saved != null && Connections.valid(saved)) return saved
        return if (preferences.getBoolean("chatgpt", false)) {
            chatGpt.state.value.active.ifBlank { Connections.key("openai") }
        } else Connections.key("openai")
    }

    internal fun savedModel(connection: String): String {        val preferences = providerPreferences
        preferences.getString("model:$connection", null)?.let { return it }
        if (Connections.providerId(connection) == "openai" && !Connections.isPlan(connection)) {
            return preferences.getString("model", "").orEmpty()
        }
        return ""
    }

    internal fun saveConnection(connection: String, model: String? = null) {
        val editor = providerPreferences.edit().putString("connection", connection)
        if (model != null) editor.putString("model:$connection", model)
        check(editor.commit())
    }

    /** The custom endpoint lives in preferences, not the registry, so it has no [Providers] entry. */
    internal fun providerLabel(connection: String): String = when {
        Connections.isPlan(connection) -> getString(R.string.chatgpt_use_plan)
        Connections.providerId(connection) == Connections.CUSTOM -> getString(R.string.provider_custom)
        else -> Providers.of(connection)?.label ?: connection
    }

    internal val extraction by lazy { KnowledgeExtraction(this, sessions, ::provider) }
    private val runtimeDelegate = lazy {
        SessionRuntime(
            this, sessions, provider(Connections.key("openai")),
            afterTurn = { extraction.start(it, automatic = true) }, providerFor = ::provider
        )
    }
    internal val runtime by runtimeDelegate
    private var sample: MockLesson? = null
    internal fun sampleLesson(saved: Bundle? = null): MockLesson {
        check(BuildConfig.DEBUG)
        return sample ?: MockLesson(this, saved).also { sample = it }
    }

    internal fun saveSample(): Bundle? = if (BuildConfig.DEBUG) sample?.save() else null

    internal fun pauseRuntime() {
        if (runtimeDelegate.isInitialized()) { runtime.stop(); extraction.foreground = false }
        if (BuildConfig.DEBUG) sample?.runtime?.stop()
    }
}
