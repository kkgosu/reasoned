package com.kkgosu.reasoned.model

internal data class ChatGptAccount(
    val id: String,
    val label: String,
    val connected: Boolean,
    val plan: Boolean
)

internal data class ChatGptModel(val slug: String, val name: String)
internal data class ChatGptStatus(
    val accounts: List<ChatGptAccount> = emptyList(),
    val active: String = "",
    val busy: Boolean = false,
    val error: String = ""
)
