package com.kkgosu.reasoned.api

import com.kkgosu.reasoned.storage.CredentialStore
import com.kkgosu.reasoned.utils.objects
import com.kkgosu.reasoned.utils.strictString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

internal fun openAiHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS)
    .callTimeout(120, TimeUnit.SECONDS)
    .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()

class OpenAiProvider(
    private val accessToken: suspend () -> String,
    private val client: OkHttpClient = openAiHttpClient(),
) : ModelProvider {
    constructor(credentials: CredentialStore, client: OkHttpClient = openAiHttpClient()) :
            this({ credentials.read() ?: throw ProviderFailure("key_missing") }, client)

    internal suspend fun models(): List<String> {
        val token = accessToken()
        val call = client.newCall(
            Request.Builder().url("https://api.openai.com/v1/models")
                .header("Authorization", "Bearer $token").build()
        )
        return coroutineScope {
            val reader = async(Dispatchers.IO) {
                call.execute().use { response ->
                    val source = response.body.source()
                    if (source.request(2_097_153)) throw ProviderFailure("model_catalog_limit")
                    val body = source.readUtf8()
                    if (!response.isSuccessful) throw ProviderFailure(
                        openAiErrorCode(body) ?: "http_${response.code}"
                    )
                    JSONObject(body).getJSONArray("data").objects()
                        .map { it.strictString("id", 100) }
                        .filter {
                            it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")) && !it.startsWith(
                                "sk-"
                            )
                        }.distinct().take(500)
                }
            }
            try {
                reader.await()
            } finally {
                call.cancel()
            }
        }
    }

    override suspend fun respond(request: JSONObject, delta: suspend (String) -> Unit): JSONObject {
        val key = accessToken()
        val call = client.newCall(
            Request.Builder().url("https://api.openai.com/v1/responses")
                .header("Authorization", "Bearer $key")
                .header("Accept", "text/event-stream")
                .post(
                    request.toString()
                        .toRequestBody("application/json; charset=utf-8".toMediaType())
                ).build()
        )
        // Cancellation must close a blocked body read as well as a request awaiting headers.
        return coroutineScope {
            val reader = async(Dispatchers.IO) {
                try {
                    call.execute().use {
                        if (!it.isSuccessful) {
                            val source = it.body.source()
                            val body = if (source.request(65_537)) "" else source.readUtf8()
                            throw ProviderFailure(
                                openAiErrorCode(body) ?: when (it.code) {
                                    401, 403 -> "unauthorized"
                                    429 -> "rate_limit"
                                    else -> "http_${it.code}"
                                }
                            )
                        }
                        // Temporary format-only diagnostics: never log headers, tokens or response content.
                        android.util.Log.d(
                            "ReasonedRuntime",
                            "transport status=${it.code}; parsing SSE"
                        )
                        // Plan responses can carry SSE under a different media type. Validate the wire
                        // framing and completed response, not the header label; never accept JSON fallback.
                        readResponseStream(it.body.source(), delta)
                    }
                } catch (error: IOException) {
                    // Closing a blocked socket can throw IOException after its coroutine was cancelled.
                    currentCoroutineContext().ensureActive()
                    throw error
                }
            }
            try {
                reader.await()
            } finally {
                call.cancel()
            }
        }
    }
}
