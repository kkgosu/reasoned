package com.kkgosu.reasoned

import com.kkgosu.reasoned.model.NativeChat
import com.kkgosu.reasoned.model.NativeCompaction
import com.kkgosu.reasoned.utils.objects
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class NativeCompactionTest {
    private val fixtures = sequenceOf(File("../fixtures/learnharness/v1"), File("fixtures/learnharness/v1")).first { it.isDirectory }
    private fun chat(messages: JSONArray) = NativeChat.parse(JSONObject().put("id", "authored_compaction")
        .put("future", "opaque").put("messages", messages).toString())
    private fun response() = JSONObject("""{"status":"completed","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"Summary"}]}],
        "usage":{"input_tokens":100,"output_tokens":20,"input_tokens_details":{"cached_tokens":30},"output_tokens_details":{"reasoning_tokens":5}}}""")

    @Test fun authoredVectorsMatchPinnedCutsAndUsageAnchors() {
        for (case in JSONObject(File(fixtures, "compaction.json").readText()).getJSONArray("cases").objects()) {
            val source = chat(case.getJSONArray("messages"))
            val raw = source.raw
            assertEquals(case.getString("name"), case.getInt("cut"), NativeCompaction.cut(source, case.getLong("keep")))
            assertEquals(case.getString("name"), case.getLong("estimate"), NativeCompaction.estimate(source, 7))
            if (case.getString("name") == "kept tail cannot advance") assertTrue(runCatching { NativeCompaction.span(source, 2) }.isFailure)
            assertEquals(raw, source.raw)
        }
    }

    @Test fun chainedSummariesPreserveHistoryOpaqueFieldsAndNativeUsageWithoutCreatingEvidence() {
        val cases = JSONObject(File(fixtures, "compaction.json").readText()).getJSONArray("cases")
        val source = chat(cases.getJSONObject(3).getJSONArray("messages"))
        val span = NativeCompaction.span(source, 4)
        assertTrue(span.contains("prior compaction summary")); assertTrue(span.contains("summary"))
        assertTrue(span.contains("[LEARNER] cccccccc")); assertFalse(span.contains("aaaaaaaa"))
        assertEquals(1, Regex("\nsummary\n").findAll(span).count())
        val completed = NativeCompaction.complete(source, 4, " New summary ", 200000, "fixture", "api", response(), 1234, turnId = "durable_run")
        val root = completed.snapshot()
        val messages = root.getJSONArray("messages").objects()
        assertEquals(source.snapshot().getJSONArray("messages").toString(), JSONArray(messages.dropLast(1)).toString())
        assertEquals("opaque", root.getString("future"))
        val marker = messages.last()
        assertEquals("u2", marker.getJSONObject("details").getString("firstKeptMessageId"))
        assertEquals("openai:fixture", marker.getJSONObject("details").getString("model"))
        assertEquals(NativeCompaction.HEADER + "\n\nNew summary", marker.getString("content"))
        val turn = root.getJSONArray("turns").getJSONObject(0)
        assertEquals("durable_run", turn.getString("id"))
        assertEquals("completed", turn.getString("reasonedStatus"))
        assertEquals("compaction", turn.getString("reasonedPurpose"))
        assertEquals(marker.getString("id"), turn.getString("messageId"))
        val usage = turn.getJSONArray("steps").getJSONObject(0)
        assertEquals(70, usage.getInt("inputTokens")); assertEquals(30, usage.getInt("cacheReadTokens"))
        assertEquals(20, usage.getInt("outputTokens")); assertEquals(5, usage.getInt("reasoningTokens"))
        assertFalse(usage.has("cacheWriteTokens")); assertFalse(usage.has("cost"))
        val wire = completed.modelMessages().objects()
        assertEquals(2, wire.size)
        assertEquals(marker.getString("content"), wire.first().getJSONArray("content").getJSONObject(0).getString("text"))
        assertEquals("dddddddd", wire.last().getString("content"))
        assertTrue(runCatching { NativeCompaction.complete(completed, 4, "again", 200000, "fixture", "api", response(), 1235) }.isFailure)
    }

    @Test fun summarySpanOmitsPrivateScratchTruncatesResultsAndKeepsPlanIdentifiers() {
        val root = JSONObject(File(fixtures, "data/chats/chat_contract_teach.json").readText())
        val messages = root.getJSONArray("messages").objects().filter { it.optString("customType") != "compaction" }
        val assistant = messages.first { it.optString("role") == "assistant" }
        assistant.getJSONArray("content").put(JSONObject().put("type", "thinking").put("thinking", "PRIVATE_SCRATCH")
            .put("thinkingSignature", "OPAQUE_SIGNATURE"))
        val result = messages.first { it.optString("role") == "toolResult" }
        result.put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "x".repeat(2001))))
        val source = NativeChat.parse(root.put("messages", JSONArray(messages)).toString())
        val span = NativeCompaction.span(source, messages.lastIndex)
        assertFalse(span.contains("PRIVATE_SCRATCH")); assertFalse(span.contains("OPAQUE_SIGNATURE"))
        assertTrue(span.contains("x".repeat(2000) + "…[truncated]")); assertFalse(span.contains("x".repeat(2001)))
        val plan = NativeCompaction.plan(source)
        assertTrue(plan.contains("[current]"))
        for (node in root.getJSONObject("tree").getJSONArray("nodes").objects()) {
            assertTrue(plan.contains("${node.getString("title")} (${node.getString("id")})"))
        }
    }
}
