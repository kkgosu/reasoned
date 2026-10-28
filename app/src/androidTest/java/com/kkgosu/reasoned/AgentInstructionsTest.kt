package com.kkgosu.reasoned

import androidx.test.platform.app.InstrumentationRegistry
import com.kkgosu.reasoned.controllers.AgentInstructions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class AgentInstructionsTest {
    private val assets get() = InstrumentationRegistry.getInstrumentation().targetContext.assets
    private fun hash(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun vendoredInstructionsAndSkillsMatchPinnedSourceBytes() {
        val lines =
            assets.open("learnharness/SOURCE.sha256").bufferedReader().use { it.readLines() }
        assertEquals(36, lines.size)
        for (line in lines) {
            val (expected, path) = line.split("  ", limit = 2)
            val bytes = assets.open("learnharness/$path").use { it.readBytes() }
            assertEquals(path, expected, hash(bytes))
        }
    }

    @Test
    fun compactionPromptIsProjectedExactlyFromPinnedSource() {
        val source = assets.open("learnharness/src/core/compaction.ts").bufferedReader()
            .use { it.readText() }
        val expected = source.substringAfter("export function buildCompactionPrompt(")
            .substringAfter("return `").substringBefore("`;")
            .replace("\${planSection}", "{{PLAN}}")
            .replace("\${input.spanText}", "{{TRANSCRIPT}}") + "\n"
        assertEquals(
            expected,
            assets.open("agents/compaction.md").bufferedReader().use { it.readText() })
    }

    @Test
    fun compositionMatchesEveryUpstreamRoleWithSeparateAndroidAppendix() {
        val compatibility = assets.open("agents/android-compatibility.md").bufferedReader()
            .use { it.readText().trim() }
        val appendix = "\n\n---\n\n$compatibility"
        for ((phase, expected) in listOf(
            "probe" to "978f21a5223716cdcb603e19b7d716b722b8f959c907dcb1260ce48ad527ec42",
            "teacher" to "f8322baf24a405b0bfce53ea02d4ddeea4264c986e22b3f305352c6a39eb95fb",
            "practice" to "a859d6e52c4115c240efbd325e9c09ed64b24a274a096ed2dcd762abd068edcc",
        )) {
            val instructions = AgentInstructions.compose(assets, phase)
            assertTrue(instructions.endsWith(appendix))
            assertEquals(
                phase,
                expected,
                hash(instructions.removeSuffix(appendix).toByteArray(Charsets.UTF_8))
            )
        }
    }
}
