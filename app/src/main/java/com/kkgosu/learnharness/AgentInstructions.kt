package com.kkgosu.learnharness

import android.content.res.AssetManager

internal object AgentInstructions {
    const val VERSION = "learnharness-0795ef197cca-v6"

    fun compose(assets: AssetManager, phase: String): String {
        val agent = when (phase) {
            "probe" -> "prober"
            "teacher", "practice" -> phase
            else -> error("Unknown agent phase")
        }
        val root = "learnharness/src/agent"
        fun fragments(directory: String) = assets.list("$root/prompts/$directory").orEmpty()
            .filter { it.matches(Regex("[0-9]+-[\\w.-]+\\.md")) }.sorted()
            .map { "prompts/$directory/$it" }

        val shared = fragments("shared").filter {
            agent != "practice" || it.substringAfterLast('/') in setOf(
                "10-voice.md", "15-accuracy.md", "16-mental-model.md", "40-formatting.md",
            )
        }
        val paths =
            (if (agent == "practice") emptyList() else listOf("system-prompt.md")) + fragments(agent) + shared
        val source = paths.map {
            assets.open("$root/$it").bufferedReader().use { reader -> reader.readText().trim() }
        }
            .filter { it.isNotEmpty() }.joinToString("\n\n---\n\n")
        val compatibility = assets.open("agents/android-compatibility.md").bufferedReader()
            .use { it.readText().trim() }
        return "$source\n\n---\n\n$compatibility"
    }
}
