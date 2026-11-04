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
        pinLegacyCustomEndpoint()
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

    /**
     * The saved provider list plus the user-configured OpenAI-compatible endpoints. A custom
     * connection pins the URL fingerprint it was created with, so changing the saved URL never
     * redirects an existing session or reuses that endpoint's key elsewhere.
     */
    internal fun providerConfig(connection: String): Provider? {
        if (Connections.providerId(connection) != Connections.CUSTOM) return Providers.of(connection)
        val preferences = providerPreferences
        val endpoint = Connections.customId(connection)
            ?: preferences.getString("legacy_custom_endpoint", null)
            ?: throw ProviderFailure("custom_endpoint_missing")
        val url = preferences.getString("custom_url:$endpoint", null)
            ?.trim()?.trimEnd('/')
            ?: throw ProviderFailure("custom_endpoint_missing")
        if (!Connections.customUrl(url)) throw ProviderFailure("custom_endpoint_missing")
        return Provider(
            id = Connections.CUSTOM,
            label = getString(R.string.provider_custom),
            wire = Wire.CHAT,
            baseUrl = url,
            docs = null,
            models = emptyList(),
            keyless = preferences.getBoolean("custom_keyless:$endpoint", false),
            // Pre-endpoint-pinning installs keep using the credential file they already have.
            credentialId = if (endpoint == preferences.getString("legacy_custom_endpoint", null))
                Connections.CUSTOM else "custom-$endpoint",
        )
    }

    private fun provider(connection: String): ModelProvider {
        if (Connections.isPlan(connection)) return OpenAiProvider(
            { chatGpt.accessToken(connection) },
            chatGpt.client
        )
        val config = providerConfig(connection) ?: throw ProviderFailure("provider_unavailable")
        return WireProvider(config, { credentialStore(config.credentialId).read().orEmpty() })
    }

    /** Curated provider models always list; discovery is an explicit refresh and never required. */
    internal suspend fun models(connection: String, refresh: Boolean = false): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            if (Connections.isPlan(connection)) return@withContext modelCatalog.available(connection, refresh) {
                chatGpt.models(connection).map { it.slug to it.name }
            }
            val provider = providerConfig(connection) ?: throw ProviderFailure("provider_unavailable")
            val wire = WireProvider(provider, { credentialStore(provider.credentialId).read().orEmpty() })
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

    /**
     * One-time upgrade from the first custom-endpoint build, which stored one shared URL and one
     * shared key. Pinning lets sessions that still hold `key:custom` keep that exact server.
     */
    private fun pinLegacyCustomEndpoint() {
        val preferences = providerPreferences
        if (preferences.contains("custom_endpoint")) return
        val url = preferences.getString("custom_url", null)?.trim()?.trimEnd('/') ?: return
        if (!Connections.customUrl(url)) return
        val endpoint = Connections.endpointId(url)
        check(
            preferences.edit()
                .putString("custom_url:$endpoint", url)
                .putBoolean("custom_keyless:$endpoint", preferences.getBoolean("custom_keyless", false))
                .putString("legacy_custom_endpoint", endpoint)
                .putString("custom_endpoint", endpoint)
                .remove("custom_url").remove("custom_keyless").commit()
        )
    }

    /** Saves an endpoint and returns the pinned connection new sessions should use. */
    internal fun saveCustomEndpoint(url: String, keyless: Boolean): String {
        require(Connections.customUrl(url)) { "Invalid custom endpoint URL" }
        val endpoint = Connections.endpointId(url)
        check(
            providerPreferences.edit().putString("custom_url:$endpoint", url)
                .putBoolean("custom_keyless:$endpoint", keyless)
                .putString("custom_endpoint", endpoint).commit()
        )
        return Connections.customConnection(endpoint)
    }

    /** The currently selected custom endpoint connection, or null before one is saved. */
    internal fun customConnection(): String? =
        providerPreferences.getString("custom_endpoint", null)?.let(Connections::customConnection)

    /** The saved default connection. Legacy `api`/`chatgpt` preferences resolve to current ids. */
    internal fun savedConnection(): String {
        val preferences = providerPreferences
        val saved = preferences.getString("connection", null)
        if (saved == Connections.key(Connections.CUSTOM)) {
            customConnection()?.let { return it }
        }
        if (saved != null && Connections.valid(saved)) return saved
        return if (preferences.getBoolean("chatgpt", false)) {
            chatGpt.state.value.active.ifBlank { Connections.key("openai") }
        } else Connections.key("openai")
    }

    internal fun savedModel(connection: String): String {
        val preferences = providerPreferences
        preferences.getString("model:$connection", null)?.let { return it }
        if (Connections.providerId(connection) == "openai" && !Connections.isPlan(connection)) {
            // Pre-provider builds stored the API-key model under the legacy `api` connection.
            preferences.getString("model:api", null)?.let { return it }
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
        Connections.providerId(connection) == Connections.CUSTOM -> {
            val host = runCatching {
                providerConfig(connection)?.baseUrl?.let { java.net.URI(it).host }
            }.getOrNull()
            if (host.isNullOrBlank()) getString(R.string.provider_custom)
            else getString(R.string.provider_custom_label, host)
        }
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
