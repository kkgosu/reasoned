package com.kkgosu.learnharness

import android.app.Application
import com.kkgosu.learnharness.api.ChatGptAuth
import com.kkgosu.learnharness.api.ModelProvider
import com.kkgosu.learnharness.api.OpenAiProvider
import com.kkgosu.learnharness.api.ResearchWeb
import com.kkgosu.learnharness.controllers.KnowledgeExtraction
import com.kkgosu.learnharness.controllers.SessionRuntime
import com.kkgosu.learnharness.storage.CredentialStore
import com.kkgosu.learnharness.storage.ModelCatalog
import com.kkgosu.learnharness.storage.SessionStore

class ReasonedApplication : Application() {
    internal val credentials by lazy { CredentialStore(this) }
    internal val searchCredentials by lazy { CredentialStore(this, "brave") }
    internal val web by lazy { ResearchWeb(searchCredentials) }
    internal val sessions by lazy { SessionStore(this) }
    internal val modelCatalog by lazy { ModelCatalog(this) }
    internal val chatGpt by lazy { ChatGptAuth(this) }
    private val apiProvider by lazy { OpenAiProvider(credentials) }
    private fun provider(connection: String): ModelProvider =
        if (connection == "api") apiProvider else OpenAiProvider(
            { chatGpt.accessToken(connection) },
            chatGpt.client
        )

    internal val extraction by lazy { KnowledgeExtraction(this, sessions, ::provider) }
    private val runtimeDelegate = lazy {
        SessionRuntime(
            this, sessions, apiProvider,
            afterTurn = { extraction.start(it, automatic = true) }, providerFor = ::provider
        )
    }
    internal val runtime by runtimeDelegate
    internal fun pauseRuntime() {
        if (runtimeDelegate.isInitialized()) runtime.stop(); extraction.foreground = false
    }
}
