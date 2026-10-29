package com.kkgosu.reasoned

import com.kkgosu.reasoned.api.NativeResponses
import com.kkgosu.reasoned.model.NativeChat
import com.kkgosu.reasoned.model.NativePlan
import com.kkgosu.reasoned.model.NativeTeaching
import com.kkgosu.reasoned.utils.objects
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class NativePlanTest {
    private val fixtures = sequenceOf(File("../fixtures/learnharness/v1"), File("fixtures/learnharness/v1")).first { it.isDirectory }
    private fun checkpoint(id: String): NativeChat {
        val root = JSONObject(File(fixtures, "data/chats/chat_contract_teach.json").readText())
        val messages = root.getJSONArray("messages").objects()
        return NativeChat.parse(root.put("messages", JSONArray(messages.take(messages.indexOfFirst { it.getString("id") == id } + 1))).toString())
    }
    private fun normalized(value: Any?): Any? = when (value) {
        is JSONObject -> value.keys().asSequence().associateWith { normalized(value.get(it)) }
        is JSONArray -> (0 until value.length()).map { normalized(value.get(it)) }
        else -> value
    }

    @Test fun authoredPlansMatchThePinnedNativeParserAndReuseBoundedDagChecks() {
        for (case in JSONObject(File(fixtures, "plans.json").readText()).getJSONArray("cases").objects()) {
            val actual = runCatching { NativePlan.parse(case.getString("mermaid")) }.getOrNull()
            assertEquals(case.getString("name"), normalized(case.optJSONObject("tree")), normalized(actual))
        }
        assertTrue(runCatching { NativePlan.parse("graph TD\n" + (1..25).joinToString("\n") { "node-$it[Node $it]" }) }.isFailure)
        assertTrue(runCatching { NativePlan.parse("graph TD\n" + "x".repeat(16_000)) }.isFailure)
    }

    @Test fun probeAndPlanResultsKeepNativeContentDetailsAndPhaseBoundaries() {
        val before = checkpoint("m_probe_done")
        val original = before.raw
        val call = before.snapshot().getJSONArray("messages").objects().last().getJSONArray("content").objects().single()
        val completed = NativeTeaching.completeTool(before, call.getString("id"), 1000)
        val added = completed.snapshot().getJSONArray("messages").objects().takeLast(2)
        val frozen = checkpoint("steer_probe_chat_contract_teach").snapshot().getJSONArray("messages").objects()
        assertEquals("plan", completed.state().phase)
        assertEquals(NativeTeaching.PROBE_ACK, added.first().getJSONArray("content").getJSONObject(0).getString("text"))
        assertEquals(frozen.first { it.getString("id") == "m_probe_done_result" }.getJSONArray("content").toString(), added.first().getJSONArray("content").toString())
        assertEquals("probe-handoff", added.last().getString("customType"))
        assertEquals(frozen.first { it.optString("customType") == "probe-handoff" }.getString("content"), added.last().getString("content"))
        assertTrue(NativeTeaching.handoffPending(completed))
        assertEquals(original, before.raw)
        assertTrue(runCatching { NativeTeaching.completeTool(completed, call.getString("id"), 1001) }.isFailure)

        val planning = checkpoint("m_plan")
        val planCall = planning.snapshot().getJSONArray("messages").objects().last().getJSONArray("content").objects().single()
        val planned = NativeTeaching.completeTool(planning, planCall.getString("id"), 2000)
        val result = planned.snapshot().getJSONArray("messages").objects().last()
        val reference = checkpoint("m_plan_result").snapshot().getJSONArray("messages").objects().last()
        assertEquals(normalized(reference.getJSONObject("details")), normalized(result.getJSONObject("details")))
        assertEquals(normalized(JSONObject(reference.getJSONArray("content").getJSONObject(0).getString("text"))),
            normalized(JSONObject(result.getJSONArray("content").getJSONObject(0).getString("text"))))
        assertEquals("plan", planned.state().phase)
        assertTrue(NativeTeaching.awaitingPlanReply(planned))
        assertFalse(NativeTeaching.awaitingPlanReply(NativeResponses.append(planned, NativeResponses.user("Let's start", 3000), 3000)))
        assertEquals("teach", checkpoint("m_predict").state().phase)
        assertFalse(NativeTeaching.awaitingPlanReply(checkpoint("m_predict")))

        val invalid = planning.snapshot()
        invalid.getJSONArray("messages").objects().last().getJSONArray("content").getJSONObject(0)
            .getJSONObject("arguments").put("mermaid", "graph TD\na --> a")
        val refused = NativeTeaching.completeTool(NativeChat.parse(invalid.toString()), planCall.getString("id"), 4000)
        assertEquals(normalized(planning.snapshot().getJSONObject("tree")), normalized(refused.snapshot().getJSONObject("tree")))
        assertFalse(refused.snapshot().getJSONArray("messages").objects().last().getJSONObject("details").getBoolean("ok"))
        assertFalse(NativeTeaching.awaitingPlanReply(refused))
    }
}
