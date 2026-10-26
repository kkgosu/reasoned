package com.kkgosu.learnharness

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.SignedJWT
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import okio.buffer
import okio.source

internal data class ChatGptAccount(val id: String, val label: String, val connected: Boolean, val plan: Boolean)
internal data class ChatGptModel(val slug: String, val name: String)
internal data class ChatGptStatus(val accounts: List<ChatGptAccount> = emptyList(), val active: String = "", val busy: Boolean = false, val error: String = "")

/** Public-client OAuth. No cookies, client secret, embedded login or backend-api endpoints. */
internal class ChatGptAuth(private val context: Context, internal val client: OkHttpClient = openAiHttpClient(), name: String = "chatgpt") {
    private val encrypted = CredentialStore(context, name, 262_144)
    private val mutex = Mutex() // Serializes rotating refresh tokens and atomic account-file updates.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(ChatGptStatus())
    val state = mutable.asStateFlow()
    private var login: Job? = null
    private var document: JSONObject? = null

    private fun load(): JSONObject = document ?: (encrypted.read()?.let(::JSONObject) ?: JSONObject()
        .put("host", "urn:uuid:${UUID.randomUUID()}").put("active", "").put("accounts", JSONArray())).also {
        encrypted.save(it.toString()); document = it
    }
    private fun persist(value: JSONObject) { encrypted.save(value.toString()); document = value; publish(value) }
    private fun copy() = JSONObject(load().toString())
    private fun accounts(doc: JSONObject) = doc.getJSONArray("accounts").objects()
    private fun account(doc: JSONObject, id: String) = accounts(doc).find { it.getString("client") == id }
        ?: throw ProviderFailure("chatgpt_sign_in_required")
    private fun publish(doc: JSONObject) {
        mutable.value = mutable.value.copy(active = doc.optString("active"), accounts = accounts(doc).map {
            ChatGptAccount(it.getString("client"), it.optString("email").ifBlank { "ChatGPT" },
                it.optString("access").isNotEmpty(), PLAN_SCOPE in it.optString("scope").split(' '))
        })
    }
    suspend fun reload() = withContext(Dispatchers.IO) { mutex.withLock { publish(load()) } }
    suspend fun select(id: String) = withContext(Dispatchers.IO) { mutex.withLock {
        val doc = copy(); account(doc, id); doc.put("active", id); persist(doc)
    } }
    fun cancelSignIn() { login?.cancel() }

    fun signIn(id: String? = null) {
        if (login?.isActive == true) return
        mutable.value = mutable.value.copy(busy = true, error = "")
        login = scope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { mutex.withLock {
                    val doc = load()
                    val profile = id?.let { account(doc, it) }
                    Triple(doc.getString("host"), profile?.let { JSONObject(it.toString()) }, accounts(doc).size)
                } }
                if (id == null && saved.third >= 10) throw ProviderFailure("chatgpt_account_limit")
                val state = randomOAuthValue()
                val nonce = randomOAuthValue()
                val verifier = randomOAuthValue()
                val callback = withContext(Dispatchers.IO) { LoopbackSignIn() }
                val result = callback.use {
                    val url = (AUTH + "/api/accounts/authorize").toHttpUrl().newBuilder()
                        .addQueryParameter("client_id", id ?: "dynamic_agent_client")
                        .addQueryParameter("ext_agent_host_id", saved.first)
                        .addQueryParameter("response_type", "code").addQueryParameter("redirect_uri", callback.redirectUri)
                        .addQueryParameter("scope", "openid profile email offline_access resource.invoke $PLAN_SCOPE")
                        .addQueryParameter("resource", RESOURCE).addQueryParameter("state", state).addQueryParameter("nonce", nonce)
                        .addQueryParameter("code_challenge_method", "S256").addQueryParameter("code_challenge", oauthChallenge(verifier))
                    if (id == null) url.addQueryParameter("agent_name_hint", "Reasoned")
                    else {
                        saved.second?.optString("id_token")?.takeIf { it.isNotEmpty() }?.let { url.addQueryParameter("id_token_hint", it) }
                        if (saved.second?.optString("scope")?.split(' ')?.contains(PLAN_SCOPE) != true) url.addQueryParameter("prompt", "consent")
                    }
                    // The listener is bound before browser launch. Never log this URL or the callback.
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.build().toString()))
                        .addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    withTimeout(300_000) { callback.await(state, id) }.also { result ->
                        withContext(Dispatchers.IO) { mutex.withLock {
                            // Retain an issued registration even if its one-use code later expires.
                            val doc = copy()
                            if (accounts(doc).none { it.getString("client") == result.clientId }) {
                                doc.getJSONArray("accounts").put(JSONObject().put("client", result.clientId).put("sub", ""))
                                persist(doc)
                            }
                            val tokens = json(Request.Builder().url(TOKEN).post(FormBody.Builder()
                                .add("grant_type", "authorization_code").add("client_id", result.clientId).add("code", result.code)
                                .add("code_verifier", verifier).add("redirect_uri", callback.redirectUri).add("resource", RESOURCE).build()).build())
                            val profile = tokenRecord(account(doc, result.clientId), tokens, nonce, refreshing = false)
                            val updated = copy()
                            val old = account(updated, result.clientId)
                            profile.keys().forEach { key -> old.put(key, profile.get(key)) }
                            updated.put("active", result.clientId); persist(updated)
                        } }
                    }
                }
                check(result.clientId.isNotEmpty())
            } catch (_: TimeoutCancellationException) { mutable.value = mutable.value.copy(error = "chatgpt_sign_in_timeout") }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.value = mutable.value.copy(error = (error as? ProviderFailure)?.reason ?: "chatgpt_sign_in_failed") }
            finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }

    /** Selected registrations, not the current settings picker, determine a saved session's billing. */
    suspend fun accessToken(id: String): String = withContext(Dispatchers.IO) { mutex.withLock {
        val doc = copy()
        val profile = account(doc, id)
        if (PLAN_SCOPE !in profile.optString("scope").split(' ')) throw ProviderFailure("chatgpt_plan_permission_required")
        if (profile.optString("access").isEmpty()) throw ProviderFailure("chatgpt_sign_in_required")
        if (profile.optLong("expires") <= System.currentTimeMillis() + 60_000) {
            val refresh = profile.optString("refresh").takeIf { it.isNotEmpty() } ?: throw ProviderFailure("chatgpt_sign_in_required")
            try {
                val tokens = json(Request.Builder().url(TOKEN).post(FormBody.Builder().add("grant_type", "refresh_token")
                    .add("client_id", id).add("refresh_token", refresh).add("resource", RESOURCE).build()).build())
                val replacement = tokenRecord(profile, tokens, null, refreshing = true)
                replacement.keys().forEach { key -> profile.put(key, replacement.get(key)) }
                persist(doc) // Persist a rotated refresh token before allowing inference.
            } catch (error: ProviderFailure) {
                if (error.reason in TERMINAL_REFRESH) { clearTokens(profile); persist(doc) }
                throw error
            }
        }
        if (PLAN_SCOPE !in profile.optString("scope").split(' ')) throw ProviderFailure("chatgpt_plan_permission_required")
        profile.getString("access")
    } }

    suspend fun models(id: String): List<ChatGptModel> {
        val token = accessToken(id)
        return withContext(Dispatchers.IO) {
            val list = json(Request.Builder().url("$RESOURCE/models").header("Authorization", "Bearer $token").build(), maxBytes = 2_097_152).getJSONArray("models")
            list.objects().filter { it.optString("visibility") == "list" }.take(100).map {
                ChatGptModel(it.strictString("slug", 100), it.strictString("display_name", 200))
            }
        }
    }

    /** Stop generation in the UI before calling. Keep host/client/verified identity for reauthorization. */
    suspend fun signOut(id: String): Boolean = withContext(Dispatchers.IO) { mutex.withLock {
        val doc = copy(); val profile = account(doc, id)
        var revoked = profile.optString("refresh").isEmpty()
        try {
            if (!revoked) {
                val discovery = discovery()
                val endpoint = trustedAuthEndpoint(discovery.getString("revocation_endpoint"))
                json(Request.Builder().url(endpoint).post(FormBody.Builder().add("token", profile.getString("refresh"))
                    .add("token_type_hint", "refresh_token").add("client_id", id).build()).build(), emptyAllowed = true)
                revoked = true
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Local logout still completes; UI links to remote disconnect. */ }
        clearTokens(profile); persist(doc)
        revoked
    } }

    /** Explicit recovery for an unreadable Keystore record. Remote grants may still exist. */
    suspend fun resetLocal() {
        login?.cancelAndJoin()
        withContext(Dispatchers.IO) { mutex.withLock {
            encrypted.forget(); document = null; mutable.value = ChatGptStatus(); publish(load())
        } }
    }

    suspend fun needsWelcome(id: String): Boolean = withContext(Dispatchers.IO) { mutex.withLock {
        val profile = account(load(), id)
        profile.optString("access").isNotEmpty() && PLAN_SCOPE in profile.optString("scope").split(' ') && !profile.optBoolean("welcomed")
    } }
    suspend fun acknowledgeWelcome(id: String) = withContext(Dispatchers.IO) { mutex.withLock {
        val doc = copy(); account(doc, id).put("welcomed", true); persist(doc)
    } }

    private suspend fun tokenRecord(old: JSONObject, tokens: JSONObject, nonce: String?, refreshing: Boolean): JSONObject {
        if (!tokens.getString("token_type").equals("Bearer", true)) throw ProviderFailure("chatgpt_token_invalid")
        val record = JSONObject(old.toString())
        val token = tokens.optString("id_token").ifEmpty { if (refreshing) old.optString("id_token") else "" }
        if (!refreshing || tokens.has("id_token")) {
            val config = discovery()
            val keys = json(Request.Builder().url(trustedAuthEndpoint(config.getString("jwks_uri"))).build())
            val identity = verifyChatGptIdentity(token, keys.toString(), record.getString("client"), nonce, tokens.strictString("access_token", 32_768))
            if (old.optString("sub").isNotEmpty() && identity.first != old.getString("sub")) throw ProviderFailure("chatgpt_account_mismatch")
            record.put("sub", identity.first).put("email", identity.second)
        }
        val lifetime = tokens.getLong("expires_in")
        if (lifetime !in 1..604800) throw ProviderFailure("chatgpt_token_invalid")
        record.put("access", tokens.strictString("access_token", 32_768))
            .put("refresh", if (tokens.has("refresh_token")) tokens.strictString("refresh_token", 32_768) else if (refreshing) old.optString("refresh") else "")
            .put("id_token", token).put("expires", System.currentTimeMillis() + lifetime * 1000)
            .put("scope", if (tokens.has("scope")) tokens.strictString("scope", 2000, true) else if (refreshing) old.optString("scope") else "")
        return record
    }
    private suspend fun discovery(): JSONObject = json(Request.Builder().url("$AUTH/.well-known/openid-configuration").build()).also {
        if (it.getString("issuer") != AUTH || it.getString("token_endpoint") != TOKEN || it.getString("authorization_endpoint") != "$AUTH/api/accounts/authorize")
            throw ProviderFailure("chatgpt_discovery_invalid")
    }
    private suspend fun json(request: Request, emptyAllowed: Boolean = false, maxBytes: Long = 262_144): JSONObject {
        val call = client.newCall(request)
        return coroutineScope {
            val result = async(Dispatchers.IO) {
                call.execute().use { response ->
                    val source = response.body.source()
                    if (source.request(maxBytes + 1)) throw ProviderFailure("chatgpt_response_limit")
                    val text = source.readUtf8()
                    if (!response.isSuccessful) throw ProviderFailure(openAiErrorCode(text) ?: "chatgpt_http_${response.code}")
                    if (emptyAllowed && text.isEmpty()) JSONObject() else JSONObject(text)
                }
            }
            try { result.await() } finally { call.cancel() }
        }
    }
    private fun clearTokens(profile: JSONObject) { listOf("access", "refresh", "id_token", "scope", "expires").forEach { profile.remove(it) } }
    companion object {
        const val AUTH = "https://auth.openai.com"
        const val RESOURCE = "https://api.openai.com/v1"
        const val TOKEN = "$AUTH/api/accounts/oauth/token"
        const val PLAN_SCOPE = "chatgpt.tokens.use.direct"
        val TERMINAL_REFRESH = setOf("invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired", "refresh_token_invalidated", "refresh_token_reused")
        fun openUsage(context: Context) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/settings/usage"))) }
        private fun trustedAuthEndpoint(value: String): String {
            val url = value.toHttpUrl()
            if (url.scheme != "https" || url.host != "auth.openai.com" || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null)
                throw ProviderFailure("chatgpt_discovery_invalid")
            return value
        }
    }
}

/** Actionable plan errors without exposing provider diagnostics or changing the session's billing. */
internal fun Context.runtimeError(reason: String): String = when (reason) {
    "subscription_sharing_usage_limit_exceeded" -> getString(R.string.chatgpt_error_limit)
    "subscription_sharing_user_not_eligible" -> getString(R.string.chatgpt_error_eligibility)
    "subscription_sharing_usage_unavailable", "subscription_sharing_user_unavailable", "chatgpt_http_503" -> getString(R.string.chatgpt_error_unavailable)
    "subscription_sharing_unsupported_capability", "subscription_sharing_route_not_supported" -> getString(R.string.chatgpt_error_unsupported)
    "chatgpt_plan_permission_required", "chatpass_v2_scope_not_authorized", "chatpass_v2_invalid_authorization_context" -> getString(R.string.chatgpt_error_permission)
    "chatgpt_sign_in_required", in ChatGptAuth.TERMINAL_REFRESH -> getString(R.string.chatgpt_error_sign_in)
    "chatgpt_sign_in_timeout" -> getString(R.string.chatgpt_error_timeout)
    "chatgpt_models_failed" -> getString(R.string.chatgpt_models_failed)
    "chatgpt_response_limit" -> getString(R.string.chatgpt_error_response_limit)
    else -> getString(R.string.runtime_error, reason)
}

internal fun randomOAuthValue(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
internal fun oauthChallenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

/** Nimbus performs signature/JWK parsing; only RS256 from the pinned issuer's JWKS is accepted. */
internal fun verifyChatGptIdentity(token: String, jwks: String, client: String, nonce: String?, accessToken: String, now: Long = System.currentTimeMillis()): Pair<String, String> {
    try {
        if (token.length > 32_768) throw IllegalArgumentException()
        val jwt = SignedJWT.parse(token)
        if (jwt.header.algorithm != JWSAlgorithm.RS256 || !jwt.header.criticalParams.isNullOrEmpty()) throw IllegalArgumentException()
        val key = JWKSet.parse(jwks).getKeyByKeyId(jwt.header.keyID) as? RSAKey ?: throw IllegalArgumentException()
        if (key.isPrivate || (key.keyUse != null && key.keyUse.identifier() != "sig") || (key.algorithm != null && key.algorithm != JWSAlgorithm.RS256)) throw IllegalArgumentException()
        if (!jwt.verify(RSASSAVerifier(key))) throw IllegalArgumentException()
        val claims = jwt.jwtClaimsSet
        if (claims.issuer != ChatGptAuth.AUTH || client !in claims.audience || claims.subject.isNullOrBlank() || claims.subject.length > 256 ||
            claims.expirationTime == null || claims.expirationTime.time <= now - 5000 || claims.issueTime == null || claims.issueTime.time > now + 5000 ||
            (claims.notBeforeTime?.time ?: 0) > now + 5000 || (claims.audience.size > 1 && claims.getStringClaim("azp") != client) ||
            (claims.getStringClaim("azp") != null && claims.getStringClaim("azp") != client) || (nonce != null && claims.getStringClaim("nonce") != nonce)) throw IllegalArgumentException()
        claims.getStringClaim("at_hash")?.let { hash ->
            val digest = MessageDigest.getInstance("SHA-256").digest(accessToken.toByteArray(Charsets.US_ASCII))
            if (hash != Base64.getUrlEncoder().withoutPadding().encodeToString(digest.copyOfRange(0, digest.size / 2))) throw IllegalArgumentException()
        }
        return claims.subject to (claims.getStringClaim("email") ?: "ChatGPT").take(254)
    } catch (_: Exception) { throw ProviderFailure("chatgpt_identity_invalid") }
}

internal class OAuthCallback(val code: String, val clientId: String)

/** Loopback only, ephemeral port, bounded HTTP parsing, one matching callback and a five-minute deadline. */
internal class LoopbackSignIn : Closeable {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 1000 }
    @Volatile private var socket: Socket? = null
    val redirectUri = "http://127.0.0.1:${server.localPort}/auth/callback"
    suspend fun await(state: String, clientId: String?): OAuthCallback = coroutineScope {
        val reader = async(Dispatchers.IO) {
            while (true) {
                currentCoroutineContext().ensureActive()
                val connection = try { server.accept() }
                catch (_: SocketTimeoutException) { continue }
                catch (error: IOException) { currentCoroutineContext().ensureActive(); throw error }
                socket = connection
                connection.use { peer ->
                    peer.soTimeout = 1000
                    try {
                        val source = peer.getInputStream().source().buffer()
                        val line = source.readUtf8LineStrict(8192).split(' ')
                        var headerBytes = 0
                        var host = ""
                        while (true) {
                            val header = source.readUtf8LineStrict(8192)
                            headerBytes += header.length + 2
                            if (headerBytes > 16384) throw IllegalArgumentException()
                            if (header.isEmpty()) break
                            if (header.substringBefore(':').equals("Host", true)) host = header.substringAfter(':').trim()
                        }
                        val validRequest = line.size == 3 && line[0] == "GET" && line[1].startsWith("/auth/callback?") && host == "127.0.0.1:${server.localPort}"
                        val url = if (validRequest) ("http://$host" + line[1]).toHttpUrl() else null
                        val unique = url != null && url.queryParameterNames.all { url.queryParameterValues(it).size == 1 }
                        val matched = unique && MessageDigest.isEqual(state.toByteArray(), url!!.queryParameter("state").orEmpty().toByteArray())
                        val body = if (matched) "Sign-in received. You can return to Reasoned." else "This sign-in callback was not accepted."
                        peer.getOutputStream().write(("HTTP/1.1 ${if (matched) "200 OK" else "400 Bad Request"}\r\nContent-Type: text/plain; charset=utf-8\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body").toByteArray())
                        if (matched) {
                            if (url!!.queryParameter("error") != null) throw ProviderFailure("chatgpt_consent_declined")
                            val returnedId = url.queryParameter("client_id") ?: clientId
                            if (returnedId == null || !returnedId.matches(Regex("oaiapp_[A-Za-z0-9_-]{1,180}")) || (clientId != null && returnedId != clientId)) throw ProviderFailure("chatgpt_client_mismatch")
                            val code = url.queryParameter("code")?.takeIf { it.length in 1..4096 } ?: throw ProviderFailure("chatgpt_callback_invalid")
                            return@async OAuthCallback(code, returnedId)
                        }
                    } catch (error: ProviderFailure) { throw error }
                    catch (_: Exception) {
                        currentCoroutineContext().ensureActive()
                        // Ignore unrelated local requests; only a matching state consumes the attempt.
                    }
                }
                socket = null
            }
            @Suppress("UNREACHABLE_CODE") error("Unreachable")
        }
        try { reader.await() } finally { close() }
    }
    override fun close() { socket?.close(); server.close() }
}
