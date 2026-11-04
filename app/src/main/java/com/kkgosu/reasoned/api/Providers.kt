package com.kkgosu.reasoned.api

import android.content.Context
import com.kkgosu.reasoned.utils.objects
import com.kkgosu.reasoned.utils.strings
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest

/**
 * Wire protocols the native client speaks. Each id matches the pi-ai api name so archived
 * message metadata stays pi-compatible.
 */
internal enum class Wire(val id: String) {
    RESPONSES("openai-responses"),
    CHAT("openai-completions"),
    ANTHROPIC("anthropic-messages"),
    GEMINI("google-generative-ai");

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id } ?: CHAT
        fun fromAsset(id: String) = entries.firstOrNull { it.name.equals(id, ignoreCase = true) }
            ?: throw IllegalArgumentException("Unknown wire in the provider asset: $id")
    }
}

/** Request-shaping flags pi-ai derives per provider/model; only what this client acts on. */
internal data class ModelCompat(
    val reasoningEffort: Boolean = true,
    val maxTokensField: String = "max_completion_tokens",
    val reasoningContent: Boolean = false,
    val thinkingFormat: String = "openai",
    val strict: Boolean = false,
    val thinkingMap: Map<String, String?> = emptyMap(),
    val adaptive: Boolean = false,
    val effort: Boolean = false,
    val canDisable: Boolean = true,
    val thinkingParam: String? = null,
    val encryptedReasoning: Boolean = false,
) {
    /** Provider value for an app-level effort, or null when the provider has no mapping. */
    fun effortValue(effort: String): String? = when {
        thinkingMap.containsKey(effort) -> thinkingMap[effort]
        effort == "none" -> null
        else -> effort
    }
}

internal data class ProviderModel(
    val id: String,
    val name: String,
    val window: Long,
    val maxOutput: Long,
    val efforts: List<String>,
    val compat: ModelCompat = ModelCompat(),
)

internal data class Provider(
    val id: String,
    val label: String,
    val wire: Wire,
    val baseUrl: String,
    val docs: String?,
    val models: List<ProviderModel>,
    /** Only the custom endpoint can be keyless; a registry provider always sends a key. */
    val keyless: Boolean = false,
    /**
     * Credential file identity. Registry providers use their id; each custom endpoint gets its
     * own store from its URL fingerprint, so a key is never reused for a different server.
     */
    val credentialId: String = id,
) {
    fun model(id: String): ProviderModel? = models.firstOrNull { it.id == id }

    /** Base URL with no trailing slash, so endpoint paths append cleanly. */
    val root: String get() = baseUrl.trimEnd('/')
}

/**
 * Session connection identity.
 *
 * `key:<provider>` is an API-key connection, `oaiapp_<account>` a ChatGPT plan connection.
 * `api` is the pre-provider spelling of `key:openai` and stays readable forever.
 */
internal object Connections {
    private const val KEY_PREFIX = "key:"
    const val CUSTOM = "custom"
    const val IMPORTED = "imported"

    fun key(provider: String) = KEY_PREFIX + provider
    fun isPlan(connection: String) = connection.startsWith("oaiapp_")
    fun isImported(connection: String) = connection == IMPORTED

    /** A custom endpoint connection pins the URL fingerprint, never just the shared `custom` id. */
    fun custom(connection: String) = connection.startsWith("$KEY_PREFIX$CUSTOM-")
    fun customConnection(endpoint: String) = key("$CUSTOM-$endpoint")

    /** The endpoint fingerprint for a pinned custom connection, or null for legacy `key:custom`. */
    fun customId(connection: String): String? =
        connection.removePrefix("$KEY_PREFIX$CUSTOM-").takeIf { custom(connection) }

    /**
     * Stable short fingerprint of a normalized custom URL. Two spellings of the same server share
     * a credential, and a different server can never reuse the previous endpoint's key.
     */
    fun endpointId(url: String): String = MessageDigest.getInstance("SHA-256")
        .digest(url.trim().trimEnd('/').toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        .take(12)

    /** The only hosts res/xml/network_security_config.xml lets cleartext through. */
    private val CLEARTEXT_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2")
    private val CUSTOM_URL = Regex("https?://[^\\s?#]{4,300}")

    /** https anywhere, http only to a host the network security config permits cleartext for. */
    fun customUrl(url: String): Boolean {
        if (!url.matches(CUSTOM_URL)) return false
        if (url.startsWith("https://")) return true
        val host = runCatching { URI(url).host }.getOrNull()
        return host != null && host.lowercase() in CLEARTEXT_HOSTS
    }

    /** Key, imported and legacy API connections speak a wire that sends tools flat; every other connection is plan-shaped. */
    fun namespacesTools(connection: String) =
        connection != "api" && connection != IMPORTED && !connection.startsWith(KEY_PREFIX)

    fun providerId(connection: String) = when {
        connection == "api" -> "openai"
        custom(connection) -> CUSTOM
        connection.startsWith(KEY_PREFIX) -> connection.removePrefix(KEY_PREFIX)
        isPlan(connection) -> "openai-codex"
        else -> connection
    }

    fun valid(connection: String) = connection == "api" ||
            connection == IMPORTED ||
            connection == KEY_PREFIX + CUSTOM ||
            (isPlan(connection) && connection.matches(Regex("oaiapp_[A-Za-z0-9_-]{1,180}"))) ||
            (custom(connection) && customId(connection)!!.matches(Regex("[a-f0-9]{12}"))) ||
            (connection.startsWith(KEY_PREFIX) &&
                    connection.removePrefix(KEY_PREFIX).matches(Regex("[a-z0-9][a-z0-9.-]{0,40}")))
}

/**
 * The connectable provider registry from assets/providers.json (generated from pi-ai 1.4.0).
 * Read-only after [load]; no credentials, network or per-session state lives here.
 */
internal object Providers {
    private var registry: List<Provider> = emptyList()

    fun load(context: Context) {
        if (registry.isNotEmpty()) return
        parse(context.assets.open("providers.json").bufferedReader().use { it.readText() })
    }

    fun parse(json: String) {
        val providers = JSONObject(json).getJSONArray("providers").objects().map { provider ->
            Provider(
                id = provider.getString("id"),
                label = provider.getString("label"),
                wire = Wire.fromAsset(provider.getString("wire")),
                baseUrl = provider.getString("baseUrl"),
                docs = provider.opt("docs") as? String,
                models = provider.getJSONArray("models").objects().map { model ->
                    ProviderModel(
                        id = model.getString("id"),
                        name = model.getString("name"),
                        window = model.getLong("window"),
                        maxOutput = model.optLong("maxOutput", 0),
                        efforts = model.getJSONArray("efforts").strings(16, 40),
                        compat = model.optJSONObject("compat")?.let(::compat) ?: ModelCompat(),
                    )
                },
            )
        }
        require(providers.isNotEmpty()) { "Empty provider registry" }
        registry = providers
    }

    private fun compat(raw: JSONObject) = ModelCompat(
        reasoningEffort = raw.optBoolean("reasoningEffort", true),
        maxTokensField = raw.optString("maxTokensField", "max_completion_tokens"),
        reasoningContent = raw.optBoolean("reasoningContent", false),
        thinkingFormat = raw.optString("thinkingFormat", "openai"),
        strict = raw.optBoolean("strict", false),
        thinkingMap = raw.optJSONObject("thinkingMap")?.let { map ->
            map.keys().asSequence().associateWith { key -> map.opt(key) as? String }
        }.orEmpty(),
        adaptive = raw.optBoolean("adaptive", false),
        effort = raw.optBoolean("effort", false),
        canDisable = raw.optBoolean("canDisable", true),
        thinkingParam = raw.opt("thinkingParam") as? String,
        encryptedReasoning = raw.optBoolean("encryptedReasoning", false),
    )

    fun all(): List<Provider> = registry
    fun byId(id: String): Provider? = registry.firstOrNull { it.id == id }

    /** The provider behind a connection, including the ChatGPT plan's OpenAI backend. */
    fun of(connection: String): Provider? = byId(Connections.providerId(connection))

    /** What an archived assistant message records for this connection. */
    fun providerName(connection: String) = Connections.providerId(connection)
    fun apiName(connection: String): String = when {
        Connections.isPlan(connection) -> "openai-codex-responses"
        Connections.providerId(connection) == Connections.CUSTOM -> Wire.CHAT.id
        else -> of(connection)?.wire?.id ?: Wire.RESPONSES.id
    }
}
