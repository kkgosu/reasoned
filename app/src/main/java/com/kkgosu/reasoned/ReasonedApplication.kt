package com.kkgosu.reasoned

import android.app.Application
import android.os.Bundle
import com.kkgosu.reasoned.controllers.MockLesson
import com.kkgosu.reasoned.api.ChatGptAuth
import com.kkgosu.reasoned.api.ModelProvider
import com.kkgosu.reasoned.api.OpenAiProvider
import com.kkgosu.reasoned.api.ResearchWeb
import com.kkgosu.reasoned.controllers.KnowledgeExtraction
import com.kkgosu.reasoned.controllers.SessionRuntime
import com.kkgosu.reasoned.storage.CredentialStore
import com.kkgosu.reasoned.storage.ModelCatalog
import com.kkgosu.reasoned.storage.SessionStore

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
    private var sample: MockLesson? = null
    internal fun sampleLesson(saved: Bundle? = null): MockLesson {
        check(BuildConfig.DEBUG)
        return sample ?: MockLesson(this, saved).also { sample = it }
    }

    internal fun saveSample(): Bundle? = if (BuildConfig.DEBUG) sample?.save() else null

    internal fun pauseRuntime() {
        if (runtimeDelegate.isInitialized()) runtime.stop(); extraction.foreground = false
        if (BuildConfig.DEBUG) sample?.runtime?.stop()
    }
}
