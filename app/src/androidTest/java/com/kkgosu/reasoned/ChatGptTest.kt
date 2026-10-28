package com.kkgosu.reasoned

import android.content.ContextWrapper
import android.content.Intent
import android.view.View
import android.widget.EditText
import android.widget.RadioGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kkgosu.reasoned.api.ChatGptAuth
import com.kkgosu.reasoned.api.ModelProvider
import com.kkgosu.reasoned.api.OpenAiProvider
import com.kkgosu.reasoned.api.ProviderFailure
import com.kkgosu.reasoned.api.openAiHttpClient
import com.kkgosu.reasoned.api.readResponseStream
import com.kkgosu.reasoned.controllers.RuntimeTools
import com.kkgosu.reasoned.controllers.SessionRuntime
import com.kkgosu.reasoned.controllers.ToolSchemas
import com.kkgosu.reasoned.fragments.ProviderSettingsFragment
import com.kkgosu.reasoned.storage.CredentialStore
import com.kkgosu.reasoned.storage.SessionStore
import com.kkgosu.reasoned.ui.text.runtimeError
import com.kkgosu.reasoned.utils.objects
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Date
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ChatGptTest {
    private val app get() = ApplicationProvider.getApplicationContext<ReasonedApplication>()
    private val pair by lazy {
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    }
    private val jwks
        get() = JWKSet(
            RSAKey.Builder(pair.public as RSAPublicKey).keyID("fixture").build()
        ).toString()

    private fun signed(
        client: String = "oaiapp_fixture",
        nonce: String = "nonce",
        issuer: String = ChatGptAuth.AUTH,
        expiration: Long = System.currentTimeMillis() + 120_000,
        subject: String = "fixture-user"
    ): String {
        val claims = JWTClaimsSet.Builder().issuer(issuer).audience(client).subject(subject)
            .issueTime(Date()).expirationTime(Date(expiration)).claim("nonce", nonce)
            .claim("email", "fixture@example.invalid").build()
        return SignedJWT(
            JWSHeader.Builder(JWSAlgorithm.RS256).keyID("fixture").build(),
            claims
        ).apply { sign(RSASSASigner(pair.private)) }.serialize()
    }

    private fun rejects(reason: String? = null, action: () -> Unit) {
        try {
            action(); fail("Invalid input accepted")
        } catch (failure: ProviderFailure) {
            if (reason != null) assertEquals(reason, failure.reason)
        }
    }

    private fun callback(uri: String): String {
        val url = uri.toHttpUrl()
        return Socket("127.0.0.1", url.port).use { socket ->
            socket.soTimeout = 3000
            socket.getOutputStream()
                .write("GET ${url.encodedPath}?${url.encodedQuery} HTTP/1.1\r\nHost: 127.0.0.1:${url.port}\r\n\r\n".toByteArray())
            socket.getInputStream().bufferedReader().readText()
        }
    }

    @Test
    fun browserRegistrationRefreshModelsRevocationAndCiphertextWithoutNetwork() = runBlocking {
        val name = "oauth-${UUID.randomUUID()}"
        val encrypted = CredentialStore(app, name, 262_144)
        var authorization = CompletableDeferred<String>()
        var nonce = ""
        var tokenCalls = 0
        var revocations = 0
        var failRefresh = false
        val modelCatalog = JSONObject().put(
            "models", JSONArray()
                .put(
                    JSONObject().put("slug", "visible-model").put("display_name", "Visible model")
                        .put("visibility", "list")
                )
                .put(
                    JSONObject().put("slug", "second-model").put("display_name", "Second model")
                        .put("visibility", "list")
                )
                .put(JSONObject().put("slug", "hidden").put("visibility", "hide"))
        )
            .put("capabilities", "é".repeat(150_000)).toString()
        var modelBody = modelCatalog
        val context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) {
                authorization.complete(intent.dataString!!)
            }
        }
        val client = openAiHttpClient().newBuilder().addInterceptor { chain ->
            val request = chain.request()
            assertTrue(request.url.host in listOf("auth.openai.com", "api.openai.com"))
            var code = 200
            val body = when (request.url.encodedPath) {
                "/.well-known/openid-configuration" -> JSONObject().put("issuer", ChatGptAuth.AUTH)
                    .put("authorization_endpoint", ChatGptAuth.AUTH + "/api/accounts/authorize")
                    .put("token_endpoint", ChatGptAuth.TOKEN)
                    .put("jwks_uri", ChatGptAuth.AUTH + "/.well-known/jwks.json")
                    .put("revocation_endpoint", ChatGptAuth.AUTH + "/revoke").toString()

                "/.well-known/jwks.json" -> jwks
                "/api/accounts/oauth/token" -> {
                    tokenCalls++
                    val form = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                    assertTrue(form.contains("client_id=oaiapp_fixture"))
                    assertFalse(form.contains("client_secret"))
                    if (form.contains("grant_type=refresh_token") && failRefresh) {
                        code = 400; "{\"error\":\"invalid_grant\"}"
                    } else JSONObject().put("token_type", "Bearer")
                        .put("access_token", "fixture-access-$tokenCalls")
                        .put("refresh_token", "fixture-refresh-$tokenCalls")
                        .put("id_token", signed(nonce = nonce))
                        .put(
                            "scope",
                            "openid profile email offline_access resource.invoke ${ChatGptAuth.PLAN_SCOPE}"
                        ).put("expires_in", 1).toString()
                }

                "/v1/models" -> {
                    assertTrue(
                        request.header("Authorization")!!.startsWith("Bearer fixture-access-")
                    )
                    modelBody
                }

                "/revoke" -> {
                    revocations++; ""
                }

                else -> error("Unexpected request, especially inference: ${request.url.encodedPath}")
            }
            okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(code).message("Local fixture")
                .body(body.toResponseBody()).build()
        }.build()
        val auth = ChatGptAuth(context, client, name)
        try {
            auth.signIn()
            val url = withTimeout(5000) { authorization.await() }.toHttpUrl()
            nonce = url.queryParameter("nonce")!!
            assertEquals("dynamic_agent_client", url.queryParameter("client_id"))
            assertEquals("Reasoned", url.queryParameter("agent_name_hint"))
            assertEquals("S256", url.queryParameter("code_challenge_method"))
            assertTrue(url.queryParameter("scope")!!.contains(ChatGptAuth.PLAN_SCOPE))
            val host = url.queryParameter("ext_agent_host_id")
            withContext(Dispatchers.IO) {
                callback(
                    url.queryParameter("redirect_uri") + "?state=" + url.queryParameter(
                        "state"
                    ) + "&code=fixture&client_id=oaiapp_fixture"
                )
            }
            withTimeout(5000) { auth.state.first { it.accounts.isNotEmpty() } }
            delay(200)
            assertTrue(
                "Callback must wait for the app to return to the foreground",
                auth.state.value.busy
            )
            assertEquals(0, tokenCalls)
            assertTrue(encrypted.read()!!.contains("oaiapp_fixture"))
            auth.setForeground(true)
            withTimeout(5000) { auth.state.first { !it.busy } }
            assertEquals("", auth.state.value.error)
            assertEquals("oaiapp_fixture", auth.state.value.active)
            assertEquals("fixture@example.invalid", auth.state.value.accounts.single().label)
            assertTrue(auth.needsWelcome("oaiapp_fixture"))
            auth.acknowledgeWelcome("oaiapp_fixture")
            assertFalse(auth.needsWelcome("oaiapp_fixture"))
            assertEquals("fixture-access-2", auth.accessToken("oaiapp_fixture"))
            assertTrue(modelCatalog.toByteArray(Charsets.UTF_8).size > 262_144)
            assertEquals(
                listOf("visible-model", "second-model"),
                auth.models("oaiapp_fixture").map { it.slug })
            modelBody += " ".repeat(2_097_152 - modelBody.toByteArray(Charsets.UTF_8).size)
            assertEquals(2, auth.models("oaiapp_fixture").size) // Exactly 2 MiB is allowed.
            modelBody += " "
            try {
                auth.models("oaiapp_fixture"); fail("Oversized catalog accepted")
            } catch (error: ProviderFailure) {
                assertEquals("chatgpt_response_limit", error.reason)
            }
            assertTrue(auth.state.value.accounts.single().connected)
            assertEquals(
                app.getString(R.string.chatgpt_error_response_limit),
                app.runtimeError("chatgpt_response_limit")
            )
            modelBody = modelCatalog
            // Substitute only this local fixture, never a real account or network client.
            val delegate = ReasonedApplication::class.java.getDeclaredField("chatGpt\$delegate")
                .apply { isAccessible = true }
            val previous = delegate.get(app)
            val preferences = app.getSharedPreferences("provider", 0)
            val wasPlan = preferences.getBoolean("chatgpt", false)
            val oldModel = preferences.getString("model:oaiapp_fixture", null)
            try {
                delegate.set(app, lazyOf(auth))
                preferences.edit().putBoolean("chatgpt", true).remove("model:oaiapp_fixture")
                    .commit()
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    scenario.onActivity {
                        me.grishka.appkit.Nav.go(
                            it,
                            ProviderSettingsFragment::class.java,
                            null
                        )
                    }
                    withTimeout(5000) {
                        var loaded = false
                        while (!loaded) {
                            scenario.onActivity {
                                loaded =
                                    it.findViewById<RadioGroup>(R.id.runtime_chatgpt_model_list)?.childCount == 2
                            }
                            delay(20)
                        }
                    }
                    InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(300, 3000)
                    scenario.onActivity { activity ->
                        assertEquals(
                            View.GONE,
                            activity.findViewById<View>(R.id.runtime_model).visibility
                        )
                        val list =
                            activity.findViewById<RadioGroup>(R.id.runtime_chatgpt_model_list)
                        val first = list.getChildAt(0) as android.widget.RadioButton
                        val second = list.getChildAt(1) as android.widget.RadioButton
                        assertEquals("Visible model", first.text.toString())
                        assertEquals("second-model", second.tag)
                        assertEquals(list.width, second.width)
                        assertEquals(0, second.paddingStart)
                        assertEquals(
                            activity.resources.getDimensionPixelSize(R.dimen.space_24),
                            second.paddingEnd
                        )
                        val indicator =
                            second.buttonDrawable as android.graphics.drawable.LayerDrawable
                        assertEquals(
                            activity.resources.getDimensionPixelSize(R.dimen.space_24),
                            indicator.getLayerInsetStart(0)
                        )
                        assertEquals(
                            activity.resources.getDimensionPixelSize(R.dimen.space_8),
                            indicator.getLayerInsetEnd(0)
                        )
                        assertEquals(
                            android.view.Gravity.CENTER_VERTICAL,
                            second.gravity and android.view.Gravity.VERTICAL_GRAVITY_MASK
                        )
                        second.performClick()
                    }
                    withTimeout(5000) {
                        while (preferences.getString(
                                "model:oaiapp_fixture",
                                ""
                            ) != "second-model"
                        ) delay(20)
                    }
                    scenario.recreate()
                    withTimeout(5000) {
                        var restored = false
                        while (!restored) {
                            scenario.onActivity { activity ->
                                val list =
                                    activity.findViewById<RadioGroup>(R.id.runtime_chatgpt_model_list)
                                restored =
                                    list?.findViewById<View>(list.checkedRadioButtonId)?.tag == "second-model"
                            }
                            delay(20)
                        }
                    }
                }
            } finally {
                delegate.set(app, previous)
                preferences.edit().putBoolean("chatgpt", wasPlan)
                    .putString("model:oaiapp_fixture", oldModel).commit()
            }
            assertTrue(auth.signOut("oaiapp_fixture"))
            assertEquals(1, revocations)
            assertFalse(auth.state.value.accounts.single().connected)
            assertFalse(encrypted.read()!!.contains("fixture-refresh"))
            authorization = CompletableDeferred()
            auth.signIn("oaiapp_fixture")
            val returning = withTimeout(5000) { authorization.await() }.toHttpUrl()
            assertEquals("oaiapp_fixture", returning.queryParameter("client_id"))
            assertEquals(host, returning.queryParameter("ext_agent_host_id"))
            assertNull(returning.queryParameter("agent_name_hint"))
            assertNull(returning.queryParameter("id_token_hint"))
            nonce = returning.queryParameter("nonce")!!
            auth.setForeground(true)
            withContext(Dispatchers.IO) {
                callback(
                    returning.queryParameter("redirect_uri") + "?state=" + returning.queryParameter(
                        "state"
                    ) + "&code=fixture"
                )
            }
            withTimeout(5000) { auth.state.first { !it.busy } }
            assertEquals("", auth.state.value.error)
            val ciphertext = java.io.File(app.noBackupFilesDir, "$name.credential").readBytes()
                .toString(Charsets.ISO_8859_1)
            assertFalse(ciphertext.contains("fixture-access")); assertFalse(ciphertext.contains("fixture@example.invalid"))
            failRefresh = true
            try {
                auth.accessToken("oaiapp_fixture"); fail("Invalid refresh accepted")
            } catch (error: ProviderFailure) {
                assertEquals("invalid_grant", error.reason)
            }
            assertFalse(auth.state.value.accounts.single().connected)
            assertTrue(encrypted.read()!!.contains("oaiapp_fixture"))
            assertFalse(encrypted.read()!!.contains("fixture-refresh"))
        } finally {
            auth.cancelSignIn(); encrypted.forget(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }

    @Test
    fun sessionBillingPersistsAndNeverFallsBackToAnApiKey() = runBlocking {
        val name = "billing-${UUID.randomUUID()}.db"
        val store = SessionStore(app, name)
        var apiCalls = 0
        val connections = mutableListOf<String>()
        val runtime = SessionRuntime(
            app,
            store,
            ModelProvider { _, _ -> apiCalls++; error("Wrong billing") }) { connection ->
            connections += connection
            ModelProvider { _, _ -> throw ProviderFailure("chatgpt_sign_in_required") }
        }
        try {
            withTimeout(3000) {
                runtime.create(
                    "A local plan session",
                    "visible-model",
                    "oaiapp_fixture"
                ).join()
            }
            val saved = store.load(runtime.state.value!!.id)!!
            assertEquals("oaiapp_fixture", saved.connection)
            withTimeout(3000) { runtime.retry().join() }
            assertEquals(listOf("oaiapp_fixture"), connections)
            assertEquals(0, apiCalls)
            assertEquals("chatgpt_sign_in_required", runtime.state.value!!.error)
            store.writableDatabase.execSQL(
                "UPDATE sessions SET checkpoint=? WHERE id=?",
                arrayOf(
                    JSONObject(saved.checkpoint()).apply { remove("connection") }.toString(),
                    saved.id
                )
            )
            assertEquals("api", store.load(saved.id)!!.connection)
            val failure = JSONObject().put("type", "response.failed").put(
                "response",
                JSONObject().put(
                    "error",
                    JSONObject().put("code", "subscription_sharing_usage_limit_exceeded")
                )
            )
            try {
                readResponseStream(Buffer().writeUtf8("data: $failure\n\n")) { }; fail("Failed stream accepted")
            } catch (error: ProviderFailure) {
                assertEquals("subscription_sharing_usage_limit_exceeded", error.reason)
            }
        } finally {
            withContext(Dispatchers.Main) { runtime.stop() }.join(); store.close(); app.deleteDatabase(
                name
            )
        }
    }

    @Test
    fun planRequestsUsePreviewCompatibleToolsAndContinueWithoutNetwork() = runBlocking {
        val name = "plan-tools-${UUID.randomUUID()}.db"
        val store = SessionStore(app, name)
        var calls = 0
        val client = openAiHttpClient().newBuilder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("https://api.openai.com/v1/responses", request.url.toString())
            assertEquals("Bearer fixture-plan-access", request.header("Authorization"))
            val body = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
            assertFalse(body.getBoolean("store")); assertTrue(body.getBoolean("stream"))
            assertFalse(body.has("max_output_tokens")); assertFalse(body.has("previous_response_id"))
            val input = body.getJSONArray("input").objects()
            assertFalse(input.any { it.optString("type") == "additional_tools" })
            val tools = body.getJSONArray("tools").getJSONObject(0)
            assertEquals("namespace", tools.getString("type"))
            assertEquals("learnharness", tools.getString("name"))
            assertEquals(
                ToolSchemas.schemas("probe").toString(),
                tools.getJSONArray("tools").toString()
            )
            if (calls > 0) {
                assertTrue(input.any {
                    it.optString("type") == "function_call_output" && it.optString(
                        "call_id"
                    ) == "plan-context"
                })
                assertTrue(input.any {
                    it.optString("type") == "function_call" && it.optString("namespace") == "learnharness" && it.optString(
                        "id"
                    ) == "fc_plan-context"
                })
                assertTrue(input.any { it.optString("encrypted_content") == "opaque-fixture-reasoning" })
            }
            val call = JSONObject().put("type", "function_call").put("status", "completed")
                .put("namespace", "learnharness")
                .put("id", if (calls == 0) "fc_plan-context" else "fc_plan-question")
                .put("call_id", if (calls == 0) "plan-context" else "plan-question")
                .put("name", if (calls++ == 0) "learning_context" else "ask_user_question")
                .put(
                    "arguments",
                    if (calls == 1) "{}" else "{\"question\":\"Explain subtraction\",\"details\":\"\",\"multiSelect\":false,\"options\":[]}"
                )
            val reasoning = JSONObject().put("type", "reasoning").put("id", "rs_fixture")
                .put("summary", JSONArray()).put("encrypted_content", "opaque-fixture-reasoning")
            val response = JSONObject().put("status", "completed")
                .put("output", JSONArray().put(reasoning).put(call))
            val event = JSONObject().put("type", "response.completed").put("response", response)
            okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("Local fixture")
                .header("Content-Type", "text/event-stream")
                .body("data: $event\n\n".toResponseBody()).build()
        }.build()
        val runtime = SessionRuntime(app, store, OpenAiProvider({ "fixture-plan-access" }, client))
        try {
            withTimeout(5000) {
                runtime.create("Subtraction", "visible-model", "oaiapp_fixture")
                    .join(); runtime.retry().join()
            }
            assertEquals(2, calls)
            assertEquals("question", runtime.state.value!!.status)
            assertEquals("Explain subtraction", runtime.state.value!!.question!!.text)
            assertFalse(runtime.state.value!!.protocol.contains("additional_tools"))
            val foreign = JSONObject().put("status", "completed").put(
                "output", JSONArray().put(
                    JSONObject()
                        .put("type", "function_call").put("name", "learning_context")
                        .put("namespace", "untrusted")
                        .put("arguments", "{}").put("call_id", "foreign")
                )
            )
            rejects("tool_denied") { RuntimeTools.validate(foreign, runtime.state.value!!) }
            foreign.getJSONArray("output").getJSONObject(0).put("namespace", "learnharness")
            rejects("tool_denied") {
                RuntimeTools.validate(
                    foreign,
                    runtime.state.value!!.copy(connection = "api")
                )
            }
            assertEquals(
                "learnharness",
                RuntimeTools.validate(foreign, runtime.state.value!!).call!!.getString("namespace")
            )
        } finally {
            withContext(Dispatchers.Main) { runtime.stop() }.join(); store.close(); app.deleteDatabase(
                name
            )
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }

    @Test
    fun nativeBillingChoiceHidesKeyAndRequiresExplicitBrowserSignIn() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                me.grishka.appkit.Nav.go(
                    it,
                    ProviderSettingsFragment::class.java,
                    null
                )
            }
            InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(300, 3000)
            scenario.onActivity { activity ->
                activity.findViewById<RadioGroup>(R.id.runtime_billing)
                    .check(R.id.runtime_use_chatgpt)
                assertEquals(
                    View.VISIBLE,
                    activity.findViewById<View>(R.id.runtime_chatgpt_fields).visibility
                )
                assertEquals(
                    View.GONE,
                    activity.findViewById<View>(R.id.runtime_api_fields).visibility
                )
                assertEquals(
                    View.GONE,
                    activity.findViewById<EditText>(R.id.runtime_model).visibility
                )
                assertEquals(
                    activity.getString(R.string.chatgpt_continue),
                    activity.findViewById<android.widget.Button>(R.id.runtime_chatgpt_sign_in).text.toString()
                )
            }
            InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(300, 3000)
            scenario.onActivity { activity ->
                val billing = activity.findViewById<RadioGroup>(R.id.runtime_billing)
                assertEquals(activity.window.decorView.width, billing.width)
                assertEquals(billing.getChildAt(0).width, billing.getChildAt(1).width)
                assertEquals(
                    0,
                    activity.findViewById<android.widget.Toolbar>(me.grishka.appkit.R.id.toolbar).menu.size()
                )
                for (id in listOf(
                    R.id.runtime_chatgpt_usage,
                    R.id.chatgpt_sign_out,
                    R.id.chatgpt_reset
                )) {
                    assertEquals(
                        activity.window.decorView.width,
                        activity.findViewById<View>(id).width
                    )
                }
                assertTrue(activity.findViewById<View>(R.id.runtime_chatgpt_sign_in).width < activity.window.decorView.width)
                assertFalse(activity.findViewById<View>(R.id.chatgpt_sign_out).isEnabled)
                assertTrue(activity.findViewById<View>(R.id.chatgpt_reset).isEnabled)
                assertEquals(billing.getChildAt(0).bottom, billing.getChildAt(1).top)
                for (index in 0 until billing.childCount) {
                    val row = billing.getChildAt(index) as android.widget.RadioButton
                    assertEquals(
                        android.view.Gravity.CENTER_VERTICAL,
                        row.gravity and android.view.Gravity.VERTICAL_GRAVITY_MASK
                    )
                    assertEquals(row.height / 2f, row.buttonDrawable!!.bounds.exactCenterY(), 1f)
                    val indicator = row.buttonDrawable as android.graphics.drawable.LayerDrawable
                    val glyph = indicator.getDrawable(0).bounds
                    val inset = activity.resources.getDimensionPixelSize(R.dimen.space_24)
                    assertEquals(
                        inset,
                        if (row.layoutDirection == View.LAYOUT_DIRECTION_RTL) row.width - glyph.right else glyph.left
                    )
                    assertEquals(inset, row.paddingEnd)
                }
                assertEquals(
                    activity.getString(R.string.chatgpt_reset_summary),
                    activity.findViewById<android.widget.TextView>(R.id.chatgpt_reset_summary).text.toString()
                )
                val models =
                    activity.findViewById<android.widget.Button>(R.id.runtime_chatgpt_models)
                assertFalse(models.isEnabled)
                assertEquals(0.4f, models.alpha, 0.01f)
                assertTrue(models.height >= 48 * activity.resources.displayMetrics.density)
                assertTrue(activity.findViewById<android.widget.TextView>(R.id.runtime_chatgpt_status).text.length < 180)
                assertNull(activity.findViewById<View>(R.id.runtime_goal))
                assertNull(activity.findViewById<View>(R.id.runtime_instructions))
            }
            scenario.recreate()
            InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(300, 3000)
            scenario.onActivity { activity ->
                assertEquals(
                    R.id.runtime_use_chatgpt,
                    activity.findViewById<RadioGroup>(R.id.runtime_billing).checkedRadioButtonId
                )
                assertEquals(
                    View.GONE,
                    activity.findViewById<View>(R.id.runtime_api_fields).visibility
                )
                assertEquals("", activity.findViewById<EditText>(R.id.runtime_key).text.toString())
                val scroll =
                    activity.findViewById<View>(R.id.runtime_billing).parent.parent as android.widget.ScrollView
                scroll.isSmoothScrollingEnabled = false
                scroll.fullScroll(View.FOCUS_DOWN)
            }
            InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(300, 3000)
            scenario.onActivity { activity ->
                val reset = activity.findViewById<View>(R.id.chatgpt_reset)
                val visible = android.graphics.Rect()
                val isVisible = reset.getGlobalVisibleRect(visible)
                assertTrue(
                    "Reset must be reachable by scrolling: visible=$visible height=${reset.height}",
                    isVisible && visible.height() == reset.height
                )
                reset.performClick()
            }
            InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(300, 3000)
            runBlocking {
                withTimeout(5000) {
                    var cancelled = false
                    while (!cancelled) {
                        val cancel =
                            InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
                                ?.findAccessibilityNodeInfosByText(
                                    InstrumentationRegistry.getInstrumentation().targetContext.getString(
                                        android.R.string.cancel
                                    )
                                )
                                ?.firstOrNull { it.className == "android.widget.Button" }
                        cancelled =
                            cancel?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true
                        if (!cancelled) delay(20)
                    }
                }
            }
            scenario.onActivity { activity ->
                activity.findViewById<RadioGroup>(R.id.runtime_billing).check(R.id.runtime_use_api)
                assertEquals(
                    View.GONE,
                    activity.findViewById<View>(R.id.runtime_chatgpt_actions).visibility
                )
            }
            InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(300, 3000)
            scenario.onActivity { activity ->
                assertEquals(
                    activity.window.decorView.width,
                    activity.findViewById<View>(R.id.runtime_forget_key).width
                )
            }
        }
    }
}
