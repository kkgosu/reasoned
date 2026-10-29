package com.kkgosu.reasoned

import com.kkgosu.reasoned.api.NativeResponses
import com.kkgosu.reasoned.api.ProviderFailure
import com.kkgosu.reasoned.controllers.RuntimeTools
import com.kkgosu.reasoned.model.NativeChat
import com.kkgosu.reasoned.model.NativePractice
import com.kkgosu.reasoned.model.NativeTeaching
import com.kkgosu.reasoned.model.RuntimeSession
import com.kkgosu.reasoned.utils.objects
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class NativePracticeTest {
    private val fixtures = sequenceOf(File("../fixtures/learnharness/v1"), File("fixtures/learnharness/v1")).first { it.isDirectory }
    private fun checkpoint(id: String): NativeChat {
        val root = JSONObject(File(fixtures, "data/chats/chat_contract_practice.json").readText())
        val messages = root.getJSONArray("messages").objects()
        return NativeChat.parse(root.put("messages", JSONArray(messages.take(messages.indexOfFirst { it.getString("id") == id } + 1))).toString())
    }
    private fun project(chat: NativeChat) = NativeTeaching.project(RuntimeSession(id = chat.id, goal = "[Authored test] Practice", model = "fixture",
        nativeRecord = chat, nativeDisplayIds = chat.parts().mapIndexed { i, part -> part to i + 10L }.toMap()))
    private fun normalized(value: Any?): Any? = when (value) {
        is JSONObject -> value.keys().asSequence().filter { it !in setOf("id", "timestamp") }.associateWith { normalized(value.get(it)) }
        is JSONArray -> (0 until value.length()).map { normalized(value.get(it)) }
        else -> value
    }
    private fun response(name: String, args: JSONObject, id: String = "fresh") = JSONObject().put("status", "completed")
        .put("output", JSONArray().put(JSONObject().put("type", "function_call").put("call_id", id).put("name", name).put("arguments", args.toString())))
    private fun refused(reason: String? = null, block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        assertNotNull("Expected refusal", error)
        if (reason != null) assertEquals(reason, (error as ProviderFailure).reason)
    }

    @Test fun nativePracticeCheckpointsKeepCommitmentsHiddenAndRevealOnlyTheOriginalSolution() {
        val active = project(checkpoint("p_pose_ack"))
        assertEquals("practice", active.phase)
        assertEquals("call_problem", active.activeProblem!!.id)
        assertFalse(active.practiceReady); assertNull(active.question)
        assertFalse(active.items.any { it.text.contains(active.activeProblem!!.solution) })
        val thinking = project(checkpoint("p_thinking"))
        assertEquals(listOf("I can write both numbers as multiples of two."), thinking.activeProblem!!.attempts)
        val concluded = project(checkpoint("p_conclude_ack"))
        assertNull(concluded.activeProblem)
        assertEquals("solved", concluded.problems.single().outcome)
        assertTrue(concluded.items.any { it.text.endsWith(active.activeProblem!!.solution) })
        val dismissed = project(checkpoint("p_dismissed"))
        assertNull(dismissed.activeProblem)
        assertEquals("dismissed", dismissed.problems.last().outcome)
        val later = project(checkpoint("p_next_again"))
        assertEquals(dismissed.items.count { it.role == com.kkgosu.reasoned.model.LessonDisplayItem.Role.PRACTICE },
            later.items.count { it.role == com.kkgosu.reasoned.model.LessonDisplayItem.Role.PRACTICE })
        assertFalse(later.practiceReady)

        for (id in listOf("p_pose", "p_conclude")) {
            val bare = checkpoint(id)
            val owner = bare.snapshot().getJSONArray("messages").objects().last()
            val call = owner.getJSONArray("content").getJSONObject(0)
            val acked = NativePractice.completeTool(bare, call.getString("id"), 1000)
            assertEquals(normalized(checkpoint(if (id == "p_pose") "p_pose_ack" else "p_conclude_ack").snapshot().getJSONArray("messages").objects().last()),
                normalized(acked.snapshot().getJSONArray("messages").objects().last()))
            refused { NativePractice.completeTool(acked, call.getString("id"), 1001) }
        }
        // Errored/dangling practice calls never activate or conclude a problem.
        assertNull(project(checkpoint("p_pose")).activeProblem)
        assertNotNull(project(checkpoint("p_conclude")).activeProblem)
        val errored = checkpoint("p_conclude_ack").snapshot()
        errored.getJSONArray("messages").objects().last().put("isError", true)
        assertNotNull(project(NativeChat.parse(errored.toString())).activeProblem)
    }

    @Test fun inputAppendsMatchPinnedPracticeMarkersAndDeferredDismissalExactly() {
        val active = checkpoint("p_pose_ack")
        val original = active.raw
        val thinking = NativePractice.input(active, "thinking", "I can write both numbers as multiples of two.", "call_problem", 1000)
        assertEquals(normalized(checkpoint("p_thinking").snapshot().getJSONArray("messages").objects().last()),
            normalized(thinking.snapshot().getJSONArray("messages").objects().last()))
        refused { NativePractice.input(active, "thinking", "work", "wrong") }
        refused { NativePractice.input(active, "next") }
        refused { NativePractice.input(active, "dismiss-problem", callId = "wrong") }
        refused { NativePractice.input(active, "thinking", " ", "call_problem") }
        refused { NativePractice.input(active, "thinking", "x".repeat(2001), "call_problem") }
        assertEquals(original, active.raw)
        var many = active
        repeat(40) { many = NativePractice.input(many, "thinking", "Attempt $it", "call_problem", 1000 + it.toLong()) }
        refused { NativePractice.input(many, "thinking", "One too many", "call_problem") }
        assertEquals(40, project(many).activeProblem!!.attempts.size)

        val dismissed = NativePractice.input(checkpoint("p_pose_again_ack"), "dismiss-problem", callId = "call_problem_again", now = 1000)
        assertEquals(normalized(checkpoint("p_dismissed").snapshot().getJSONArray("messages").objects().last()),
            normalized(dismissed.snapshot().getJSONArray("messages").objects().last()))
        assertEquals("call_problem_again", dismissed.state().pendingDismissal)
        assertFalse(dismissed.modelMessages().toString().contains("problem-dismissed"))
        val next = NativePractice.input(dismissed, "next", now = 1001)
        assertEquals(normalized(JSONArray(checkpoint("p_next_again").snapshot().getJSONArray("messages").objects().takeLast(3))),
            normalized(JSONArray(next.snapshot().getJSONArray("messages").objects().takeLast(3))))
        assertNull(next.state().pendingDismissal)
        val again = NativePractice.input(next, "text", "Question about the last problem", now = 1002)
        assertEquals(next.snapshot().getJSONArray("messages").length() + 1, again.snapshot().getJSONArray("messages").length())
        assertTrue(NativeResponses.input(next, "fixture", "api").toString().contains("call_conc_call_problem_again"))
    }

    @Test fun nativePracticeValidationUsesNativeNullsOptionalTargetsAndCleanActiveGates() {
        val tools = NativePractice.tools()
        assertEquals(setOf("problem", "conclude"), tools.objects().map { it.getString("name") }.toSet())
        val active = project(checkpoint("p_pose_ack"))
        val args = checkpoint("p_conclude").snapshot().getJSONArray("messages").objects().last().getJSONArray("content").getJSONObject(0).getJSONObject("arguments")
        RuntimeTools.validate(response("conclude", args), active, tools) // No invented minimum-attempt requirement.
        refused("practice_no_problem") { RuntimeTools.validate(response("conclude", args), project(checkpoint("p_conclude_ack")), tools) }
        refused { RuntimeTools.validate(response("conclude", JSONObject(args.toString()).put("rung", "")), active, tools) }
        refused { RuntimeTools.validate(response("conclude", JSONObject(args.toString()).put("outcome", "dismissed")), active, tools) }
        val pose = checkpoint("p_pose").snapshot().getJSONArray("messages").objects().last().getJSONArray("content").getJSONObject(0).getJSONObject("arguments")
        refused("practice_problem_active_or_limit") { RuntimeTools.validate(response("problem", pose), active, tools) }
        val idle = project(checkpoint("p_conclude_ack"))
        refused("practice_target") { RuntimeTools.validate(response("problem", pose), idle, tools) }
        RuntimeTools.validate(response("problem", pose), idle, tools, nativeTargets = setOf("n_selected"))
        val untagged = JSONObject(pose.toString()).apply { remove("target") }
        RuntimeTools.validate(response("problem", untagged), idle, tools)
        RuntimeTools.validate(response("problem", JSONObject(untagged.toString()).put("target", JSONArray())), idle, tools)
        val generated = NativeResponses.append(checkpoint("p_conclude_ack"), NativeResponses.assistant(
            response("problem", JSONObject(untagged.toString()).put("target", JSONArray())), "fixture", "api", 1000), 1000)
        val canonical = NativePractice.completeTool(generated, "fresh", 1000).snapshot().getJSONArray("messages").objects()
        val posed = canonical[canonical.lastIndex - 1]
        assertFalse(posed.getJSONArray("content").getJSONObject(0).getJSONObject("arguments").has("target"))
        assertTrue(posed.getJSONObject("reasonedResponses").getJSONArray("output").getJSONObject(0).getString("arguments").contains("target"))
        refused("tool_denied") { RuntimeTools.validate(response("research", JSONObject()), active, tools) }
        refused("tool_denied") { RuntimeTools.validate(response("problem", untagged), idle, JSONArray()) }
    }
}
