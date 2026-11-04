package com.kkgosu.reasoned

import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kkgosu.reasoned.api.ChatGptAuth
import com.kkgosu.reasoned.api.Connections
import com.kkgosu.reasoned.fragments.NewSessionFragment
import com.kkgosu.reasoned.fragments.ProviderSettingsFragment
import com.kkgosu.reasoned.storage.CredentialStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ProviderSetupTest {
    private val app get() = ApplicationProvider.getApplicationContext<ReasonedApplication>()
    private val preferences get() = app.getSharedPreferences("provider", 0)

    private fun settle() =
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(300, 3000)

    private fun clickDialogText(label: String) = runBlocking {
        withTimeout(5000) {
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            while (true) {
                var node = automation.rootInActiveWindow
                    ?.findAccessibilityNodeInfosByText(label)
                    ?.firstOrNull {
                        it.isClickable || it.className?.endsWith("CheckedTextView") == true
                    }
                while (node != null) {
                    if (node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) {
                        return@withTimeout
                    }
                    node = node.parent
                }
                kotlinx.coroutines.delay(20)
            }
        }
    }

    private fun clickDialog(label: String) {
        try {
            clickDialogText(label)
        } catch (error: Exception) {
            throw AssertionError("Dialog row '$label' not found; visible=${activeLabels()}", error)
        }
    }

    /** Diagnostic only: the active window's visible labels when a dialog row cannot be clicked. */
    private fun activeLabels(): List<String> {
        val labels = mutableListOf<String>()
        fun walk(node: android.view.accessibility.AccessibilityNodeInfo?) {
            node ?: return
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let(labels::add)
            for (index in 0 until node.childCount) walk(node.getChild(index))
        }
        walk(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)
        return labels
    }

    /** A changed URL is a different endpoint: its key and its sessions never move. */
    @Test
    fun customEndpointKeysAndSessionsStayPinnedToTheirServer() {
        val oldEndpoint = preferences.getString("custom_endpoint", null)
        val oldConnection = preferences.getString("connection", null)
        val oldLegacy = preferences.getString("legacy_custom_endpoint", null)
        preferences.edit().remove("legacy_custom_endpoint").commit()
        val first = app.saveCustomEndpoint("https://first.example/v1", false)
        val second = app.saveCustomEndpoint("https://second.example/v1", false)
        val oldSecondModel = preferences.getString("model:$second", null)
        val firstProvider = requireNotNull(app.providerConfig(first))
        val secondProvider = requireNotNull(app.providerConfig(second))
        val firstKey = app.credentialStore(firstProvider.credentialId)
        val secondKey = app.credentialStore(secondProvider.credentialId)
        try {
            assertNotEquals(first, second)
            assertEquals("https://first.example/v1", firstProvider.baseUrl)
            assertEquals("https://second.example/v1", secondProvider.baseUrl)
            firstKey.save("test-only-first-endpoint-key-123456")
            assertNull(secondKey.read())
            assertEquals("test-only-first-endpoint-key-123456", firstKey.read())

            // Saving a different model for the second endpoint does not touch the first.
            app.saveConnection(second, "second-model")
            assertEquals("", app.savedModel(first))
            assertEquals("second-model", app.savedModel(second))
            assertEquals("https://first.example/v1", app.providerConfig(first)!!.baseUrl)
            // A legacy bare key:custom session must never follow a newly saved endpoint.
            assertTrue(
                runCatching { app.providerConfig(Connections.key(Connections.CUSTOM)) }.isFailure
            )
        } finally {
            firstKey.forget(); secondKey.forget()
            preferences.edit().putString("custom_endpoint", oldEndpoint)
                .putString("legacy_custom_endpoint", oldLegacy)
                .putString("connection", oldConnection)
                .putString("model:$second", oldSecondModel)
                .commit()
        }
    }

    /** Saving the endpoint the key was typed for must not throw that key away. */
    @Test
    fun savingTheSameEndpointKeepsTheTypedKey() {
        val oldEndpoint = preferences.getString("custom_endpoint", null)
        val oldLegacy = preferences.getString("legacy_custom_endpoint", null)
        val oldConnection = preferences.getString("connection", null)
        val url = "https://keep-key.example/v1"
        val endpoint = Connections.endpointId(url)
        val connection = Connections.customConnection(endpoint)
        try {
            preferences.edit().remove("legacy_custom_endpoint").remove("custom_endpoint")
                .putString("connection", Connections.key(Connections.CUSTOM)).commit()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity {
                    me.grishka.appkit.Nav.go(it, ProviderSettingsFragment::class.java, null)
                }
                settle()
                scenario.onActivity { activity ->
                    activity.findViewById<EditText>(R.id.runtime_key)
                        .setText("test-only-custom-endpoint-key-123")
                    activity.findViewById<EditText>(R.id.runtime_custom_url).setText(url)
                    activity.findViewById<Button>(R.id.runtime_custom_save).performClick()
                }
                runBlocking {
                    withTimeout(5000) {
                        while (preferences.getString("custom_endpoint", null) != endpoint) {
                            kotlinx.coroutines.delay(20)
                        }
                    }
                }
                settle()
                scenario.onActivity { activity ->
                    assertEquals(
                        "test-only-custom-endpoint-key-123",
                        activity.findViewById<EditText>(R.id.runtime_key).text.toString()
                    )
                }
            }
        } finally {
            app.providerConfig(connection)?.let { app.credentialStore(it.credentialId).forget() }
            preferences.edit().putString("custom_endpoint", oldEndpoint)
                .putString("legacy_custom_endpoint", oldLegacy)
                .putString("connection", oldConnection)
                .remove("custom_url:$endpoint").remove("custom_keyless:$endpoint").commit()
        }
    }

    /** A key typed for provider A must be gone before provider B can save it. */
    @Test
    fun changingProviderClearsTheTypedKey() {
        val oldConnection = preferences.getString("connection", null)
        try {
            preferences.edit().putString("connection", Connections.key("openai")).commit()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity {
                    me.grishka.appkit.Nav.go(it, ProviderSettingsFragment::class.java, null)
                }
                settle()
                scenario.onActivity { activity ->
                    activity.findViewById<EditText>(R.id.runtime_key)
                        .setText("test-only-unsaved-key-for-openai")
                    activity.findViewById<Button>(R.id.runtime_provider).performClick()
                }
                clickDialog("DeepSeek")
                settle()
                scenario.onActivity { activity ->
                    assertEquals(
                        "",
                        activity.findViewById<EditText>(R.id.runtime_key).text.toString()
                    )
                    assertTrue(
                        activity.findViewById<Button>(R.id.runtime_provider).text.toString()
                            .contains("DeepSeek")
                    )
                }
            }
        } finally {
            preferences.edit().putString("connection", oldConnection).commit()
        }
    }

    /** Choosing an account in Settings must change the account used for new sessions. */
    @Test
    fun accountPickerSelectsTheAccountThatWillBeCharged() {
        val name = "provider-accounts-${UUID.randomUUID()}"
        val store = CredentialStore(app, name, 262_144)
        val auth = ChatGptAuth(app, name = name)
        val delegate = ReasonedApplication::class.java.getDeclaredField("chatGpt\$delegate")
            .apply { isAccessible = true }
        val previous = delegate.get(app)
        val oldConnection = preferences.getString("connection", null)
        val oldPlan = preferences.getBoolean("chatgpt", false)
        try {
            store.save(
                JSONObject().put("host", "urn:uuid:${UUID.randomUUID()}").put("active", "oaiapp_a")
                    .put(
                        "accounts", JSONArray()
                            .put(account("oaiapp_a", "First account"))
                            .put(account("oaiapp_b", "Second account"))
                    ).toString()
            )
            runBlocking { auth.reload() }
            delegate.set(app, lazyOf(auth))
            preferences.edit().putBoolean("chatgpt", true)
                .putString("connection", "oaiapp_a").commit()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity {
                    me.grishka.appkit.Nav.go(it, ProviderSettingsFragment::class.java, null)
                }
                settle()
                scenario.onActivity { activity ->
                    assertEquals(
                        "First account",
                        activity.findViewById<Button>(R.id.runtime_chatgpt_account).text.toString()
                    )
                    activity.findViewById<Button>(R.id.runtime_chatgpt_account).performClick()
                }
                clickDialog("Second account")
                runBlocking {
                    withTimeout(5000) {
                        while (auth.state.value.active != "oaiapp_b" ||
                            preferences.getString("connection", null) != "oaiapp_b"
                        ) kotlinx.coroutines.delay(20)
                    }
                }
                settle()
                assertEquals("oaiapp_b", auth.state.value.active)
                assertEquals("oaiapp_b", preferences.getString("connection", null))
                scenario.onActivity { activity ->
                    assertEquals(
                        "Second account",
                        activity.findViewById<Button>(R.id.runtime_chatgpt_account).text.toString()
                    )
                }
            }
        } finally {
            delegate.set(app, previous)
            preferences.edit().putBoolean("chatgpt", oldPlan)
                .putString("connection", oldConnection).commit()
            store.forget()
        }
    }

    @Test
    fun savedModelKeepsTheLegacyApiKeyPreference() {
        val oldPreferred = preferences.getString("model:key:openai", null)
        val oldLegacy = preferences.getString("model:api", null)
        try {
            preferences.edit().remove("model:key:openai").putString("model:api", "legacy-model")
                .commit()
            assertEquals("legacy-model", app.savedModel(Connections.key("openai")))
            preferences.edit().putString("model:key:openai", "new-model").commit()
            assertEquals("new-model", app.savedModel(Connections.key("openai")))
        } finally {
            preferences.edit().putString("model:key:openai", oldPreferred)
                .putString("model:api", oldLegacy).commit()
        }
    }

    @Test
    fun newSessionNoticeNamesTheSelectedProvider() {
        val oldConnection = preferences.getString("connection", null)
        val oldModel = preferences.getString("model:key:deepseek", null)
        try {
            preferences.edit().putString("connection", Connections.key("deepseek"))
                .putString("model:key:deepseek", "deepseek-flash").commit()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.showFragmentClearingBackStack(NewSessionFragment()) }
                settle()
                scenario.onActivity { activity ->
                    val notice =
                        activity.findViewById<TextView>(R.id.session_local_notice).text.toString()
                    assertTrue(notice.contains("DeepSeek"))
                    assertFalse(notice.contains("OpenAI"))
                    assertTrue(
                        activity.findViewById<TextView>(R.id.session_research_notice)
                            .text.toString().contains("DeepSeek")
                    )
                }
            }
        } finally {
            preferences.edit().putString("connection", oldConnection)
                .putString("model:key:deepseek", oldModel).commit()
        }
    }

    private fun account(id: String, email: String) = JSONObject()
        .put("client", id).put("email", email).put("access", "fixture-access")
        .put("refresh", "fixture-refresh").put("welcomed", true)
        .put("scope", "openid offline_access ${ChatGptAuth.PLAN_SCOPE}")
}
