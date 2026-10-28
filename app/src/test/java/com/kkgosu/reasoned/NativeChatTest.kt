package com.kkgosu.reasoned

import com.kkgosu.reasoned.model.NativeChat
import com.kkgosu.reasoned.utils.objects
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NativeChatTest {
    private val fixtures = sequenceOf(File("../fixtures/learnharness/v1"), File("fixtures/learnharness/v1"))
        .first { it.isDirectory }
    private val cases get() = JSONObject(File(fixtures, "cases.json").readText())
    private fun chat(id: String) = NativeChat.parse(File(fixtures, "data/chats/$id.json").readText())
    private fun prefix(id: String, through: String): NativeChat {
        val record = chat(id).snapshot()
        val messages = record.getJSONArray("messages").objects()
        val end = messages.indexOfFirst { it.getString("id") == through }
        check(end >= 0)
        return NativeChat.parse(record.put("messages", JSONArray(messages.take(end + 1))).toString())
    }
    private fun normalized(value: Any?): Any? = when (value) {
        is JSONObject -> value.keys().asSequence().associateWith { normalized(value.get(it)) }
        is JSONArray -> (0 until value.length()).map { normalized(value.get(it)) }
        JSONObject.NULL -> null
        else -> value
    }
    private fun same(expected: JSONObject, actual: JSONObject) = assertEquals(normalized(expected), normalized(actual))

    @Test
    fun all24NativeRestorationCheckpointsMatchTheFrozenReference() {
        val checkpoints = cases.getJSONArray("checkpoints").objects()
        assertEquals(24, checkpoints.size)
        checkpoints.forEach { checkpoint ->
            val name = checkpoint.getString("name")
            val original = prefix(checkpoint.getString("chat"), checkpoint.getString("through"))
            val state = original.state()
            val expected = checkpoint.getJSONObject("expect")
            if (expected.has("phase")) assertEquals(name, expected.getString("phase"), state.phase)
            if (expected.has("pending")) assertEquals(name, expected.opt("pending").takeUnless { it == JSONObject.NULL }, state.pending?.id)
            if (expected.has("active")) assertEquals(name, expected.opt("active").takeUnless { it == JSONObject.NULL }, state.activeProblem?.id)
            if (expected.has("dismissal")) assertEquals(name, expected.opt("dismissal").takeUnless { it == JSONObject.NULL }, state.pendingDismissal)
            if (expected.has("current")) assertEquals(name, expected.opt("current").takeUnless { it == JSONObject.NULL }, state.current)
            if (expected.has("visited")) assertEquals(name, normalized(expected.getJSONArray("visited")), state.visited.toList())
            assertEquals(name, expected.getBoolean("continuation"), state.continuation)
            if (expected.has("retry")) assertEquals(name, expected.getBoolean("retry"), state.retryDrops.isNotEmpty())
            if (expected.has("retryDrops")) assertEquals(name, (normalized(expected.getJSONArray("retryDrops")) as List<*>).toSet(), state.retryDrops)
            assertEquals(original, NativeChat.parse(original.raw))
        }
    }

    @Test
    fun allSevenAnswersKeepTeacherCommitmentsOutOfModelContent() {
        val answers = cases.getJSONArray("answers").objects()
        assertEquals(7, answers.size)
        answers.forEach { case ->
            val full = case.getJSONObject("answer")
            val before = full.toString()
            same(case.getJSONObject("compact"), NativeChat.compactAnswer(full))
            assertEquals(before, full.toString())
        }
    }

    @Test
    fun rawRecordAndIndependentSnapshotsPreserveUnknownMetadataAndInlineArtifacts() {
        for (id in listOf("chat_contract_teach", "chat_contract_practice", "chat_contract_interrupted", "chat_contract_artifacts")) {
            val source = File(fixtures, "data/chats/$id.json").readText()
            val original = NativeChat.parse(source)
            original.snapshot().put("messages", JSONArray()).put("fixtureOnly", "changed")
            original.state().pending?.input?.put("correctAnswer", "changed")
            original.modelMessages().objects().forEach { it.put("content", "changed") }
            assertEquals(source, original.raw)
            same(JSONObject(source), original.snapshot())
            assertEquals(original.parts().size, original.parts().toSet().size)
        }
        val legacyVersion = chat("chat_contract_practice")
        assertFalse(legacyVersion.snapshot().has("version"))
        legacyVersion.state(); legacyVersion.modelMessages()
        assertFalse(legacyVersion.snapshot().has("version"))
    }

    @Test
    fun compactionIsOnlyAProjectionAndMissingKeptIdentityFallsBackToFullHistory() {
        val original = chat("chat_contract_teach")
        val wire = original.modelMessages().objects()
        assertTrue(wire.first().getJSONArray("content").getJSONObject(0).getString("text").startsWith("[SESSION HISTORY COMPACTED]"))
        assertTrue(wire.none { it.has("id") || it.optString("role") == "custom" })
        assertTrue(wire.none { it.optString("toolName") == "probe" })
        assertTrue(wire.any { it.optString("toolName") == "explain" })
        val messages = original.snapshot().getJSONArray("messages")
        messages.getJSONObject(messages.length() - 1).getJSONObject("details").put("firstKeptMessageId", "missing")
        val fallback = NativeChat.parse(original.snapshot().put("messages", messages).toString())
        assertTrue(fallback.modelMessages().objects().any { it.optString("toolName") == "probe" })
        assertTrue(fallback.snapshot().getJSONArray("messages").length() > wire.size)
    }

    @Test
    fun unansweredCallsAreRepairedOnlyOnTheWireAndNeverForFailedGenerations() {
        val pending = prefix("chat_contract_teach", "m_probe")
        val result = pending.modelMessages().objects().last()
        assertEquals("toolResult", result.getString("role"))
        assertEquals("call_probe_1", result.getString("toolCallId"))
        assertEquals(NativeChat.INTERRUPTED_TEXT, result.getJSONArray("content").getJSONObject(0).getString("text"))
        assertEquals("assistant", pending.snapshot().getJSONArray("messages").objects().last().getString("role"))
        val failed = chat("chat_contract_interrupted")
        assertTrue(failed.modelMessages().objects().none { it.optString("role") == "toolResult" })
        val practice = chat("chat_contract_practice")
        assertTrue(practice.modelMessages().objects().none { it.optString("role") == "custom" })
        assertTrue(practice.modelMessages().objects().any { it.optString("toolName") == "conclude" })
    }

    @Test
    fun probeCompletionRequiresEarlierSuccessfulGenerationAndMatchingCleanResult() {
        val good = prefix("chat_contract_teach", "m_probe_done_result").snapshot()
        fun phase(change: (List<JSONObject>) -> Unit): String {
            val record = JSONObject(good.toString())
            change(record.getJSONArray("messages").objects())
            return NativeChat.parse(record.toString()).state().phase
        }
        assertEquals("plan", phase { })
        assertEquals("plan", phase { it.last().remove("details") })
        assertEquals("probe", phase { it.last().put("toolName", "another_tool") })
        assertEquals("probe", phase { it.last().put("toolCallId", "another_call") })
        assertEquals("probe", phase { it.last().put("isError", true) })
        assertEquals("probe", phase { it.last().put("details", JSONObject().put("ok", false)) })
        for (stop in listOf("error", "aborted")) {
            assertEquals("probe", phase { list -> list.first { it.getString("id") == "m_probe_done" }.put("stopReason", stop) })
        }
        val messages = good.getJSONArray("messages").objects().toMutableList()
        val ack = messages.removeAt(messages.lastIndex)
        messages.add(0, ack)
        assertEquals("probe", NativeChat.parse(good.put("messages", JSONArray(messages)).toString()).state().phase)
    }

    @Test
    fun unsafeIdentityAndFutureVersionAreRefusedWithoutNormalizingLegacyRecords() {
        val original = chat("chat_contract_teach").snapshot()
        for (version in listOf<Any>(2, 0, "1", JSONObject.NULL)) {
            assertThrows(IllegalArgumentException::class.java) {
                NativeChat.parse(JSONObject(original.toString()).put("version", version).toString())
            }
        }
        val system = JSONObject(original.toString()).apply {
            getJSONArray("messages").getJSONObject(0).put("role", "system")
        }
        assertThrows(IllegalArgumentException::class.java) { NativeChat.parse(system.toString()) }
        val messages = original.getJSONArray("messages")
        messages.getJSONObject(1).put("id", messages.getJSONObject(0).getString("id"))
        assertThrows(IllegalArgumentException::class.java) { NativeChat.parse(original.toString()) }
        assertThrows(IllegalArgumentException::class.java) { NativeChat.parse(" ".repeat(NativeChat.MAX_BYTES + 1)) }
    }
}
