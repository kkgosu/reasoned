package com.kkgosu.reasoned

import com.kkgosu.reasoned.model.NativeChat
import com.kkgosu.reasoned.model.NativeGraphSelection
import com.kkgosu.reasoned.utils.objects
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NativeGraphSelectionTest {
    private val fixtures = sequenceOf(File("../fixtures/learnharness/v1"), File("fixtures/learnharness/v1"))
        .first { it.isDirectory }
    private fun graph(name: String) = File(fixtures, "data/graph/$name").readText()
    private fun chat(id: String) = NativeChat.parse(File(fixtures, "data/chats/$id.json").readText())
    private val teach get() = chat("chat_contract_teach")
    private val practice get() = chat("chat_contract_practice")
    private fun lines(raw: String) = raw.split('\n').filter { it.isNotBlank() }
    private fun select(nodes: String = graph("nodes.jsonl"), chains: String = graph("chains.jsonl"),
        chats: List<NativeChat> = listOf(teach), format: String? = graph("format.json")) =
        NativeGraphSelection.select(chats, nodes, chains, format)

    @Test
    fun sharedNativeFixtureKeepsDefinitionsEndpointsAndOnlySelectedAssertionsInOriginalOrder() {
        val expected = JSONObject(File(fixtures, "cases.json").readText()).getJSONObject("graphSelection")
        val nodes = graph("nodes.jsonl")
        val chains = graph("chains.jsonl")
        val chats = (0 until expected.getJSONArray("chats").length()).map {
            chat(expected.getJSONArray("chats").getString(it))
        }
        val result = select(nodes, chains, chats)
        fun selected(raw: String, indices: JSONArray) = (0 until indices.length()).map { lines(raw)[indices.getInt(it)] }
        assertEquals(selected(nodes, expected.getJSONArray("nodeLines")), result.nodes)
        assertEquals(selected(chains, expected.getJSONArray("chainLines")), result.chains)
        assertEquals(expected.getJSONArray("externalProvenance").objects().map {
            NativeGraphSelection.Reference(it.getString("chatId"), it.getString("messageId"))
        }.toSet(), result.externalProvenance)
        assertFalse(result.nodes.any { JSONObject(it).getString("id") == "n_unrelated" })
        assertFalse(result.chains.any { JSONObject(it).getString("id") == "arg_unrelated" })
        assertEquals(result, select(nodes, chains, chats, format = null)) // Native lazy v1.
        assertEquals(result, select(nodes, chains, chats + practice))
        assertEquals(nodes, graph("nodes.jsonl"))
        assertEquals(chains, graph("chains.jsonl"))
        chats.forEach { assertEquals(it.raw, chat(it.id).raw) }
    }

    @Test
    fun selectingALaterEquivalentChainKeepsItsFirstCanonicalIdWithoutPullingOtherAssertions() {
        val chains = lines(graph("chains.jsonl")).map { JSONObject(it) }
        chains[1].put("provenance", JSONArray().put(JSONObject()
            .put("chatId", practice.id).put("messageId", "")))
        val raw = chains.joinToString("\n")
        val result = select(chains = raw, chats = listOf(practice))
        assertEquals(chains.take(2).map { it.toString() }, result.chains)
        assertEquals(listOf("arg_selected", "arg_same_argument"), result.chains.map { JSONObject(it).getString("id") })
        // Required endpoints retain first definitions, not the teaching lesson's later link-append.
        assertEquals(listOf(0, 2, 3).map { lines(graph("nodes.jsonl"))[it] }, result.nodes)
        assertTrue(result.externalProvenance.contains(NativeGraphSelection.Reference(teach.id, "m_teach")))
        assertEquals(raw, chains.joinToString("\n"))
    }

    @Test
    fun unknownPayloadAndRawLineWhitespaceSurviveAndAnUnrelatedSelectionIsEmpty() {
        val nodes = lines(graph("nodes.jsonl")).toMutableList()
        nodes[0] = " \t" + JSONObject(nodes[0]).put("opaque", JSONObject()
            .put("signature", "AUTHORED_OPAQUE").put("nested", JSONArray().put("keep"))).toString() + "\r"
        val raw = nodes.joinToString("\n") + "\n"
        assertEquals(nodes.take(4), select(nodes = raw).nodes)
        assertTrue(select(nodes = raw).nodes.first().contains("AUTHORED_OPAQUE"))
        val empty = select(chats = listOf(practice))
        assertTrue(empty.nodes.isEmpty() && empty.chains.isEmpty() && empty.externalProvenance.isEmpty())
        assertEquals(empty, select(nodes = "", chains = "", chats = listOf(practice)))
    }

    @Test
    fun invalidSelectionVersionsIdentitiesReferencesAndBoundsFailWithoutReturningAPartialGraph() {
        assertThrows(IllegalArgumentException::class.java) { select(chats = emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { select(chats = listOf(teach, teach)) }
        for (version in listOf("2", "0", "\"1\"", "null", "true")) {
            assertThrows(IllegalArgumentException::class.java) { select(format = "{\"version\":$version}") }
        }
        val goodChains = lines(graph("chains.jsonl")).map { JSONObject(it) }
        fun rejectedChain(change: (JSONObject) -> Unit) {
            val changed = JSONObject(goodChains.last().toString()).apply(change)
            assertThrows(RuntimeException::class.java) { select(chains = graph("chains.jsonl") + "\n" + changed) }
        }
        rejectedChain { it.put("conclusion", "n_missing") }
        rejectedChain { it.put("id", "arg_selected") } // Same ID, different argument.
        rejectedChain { it.put("id", "n_shared") }
        rejectedChain { it.put("premises", JSONArray().put("n_shared").put("n_shared")) }
        rejectedChain { it.put("premises", JSONArray()) }
        rejectedChain { it.put("premises", JSONArray().put(7)) }
        rejectedChain { it.put("provenance", JSONArray()) }
        rejectedChain { it.getJSONArray("provenance").getJSONObject(0)
            .put("chatId", teach.id).put("messageId", "missing_message") }
        assertThrows(RuntimeException::class.java) { select(nodes = graph("nodes.jsonl") + "\nnot-json") }
        assertThrows(IllegalArgumentException::class.java) {
            select(nodes = (lines(graph("nodes.jsonl")).first() + "\n").repeat(10_001))
        }
        assertThrows(IllegalArgumentException::class.java) { select(nodes = "x".repeat(256 * 1024 + 1)) }
        assertThrows(IllegalArgumentException::class.java) { select(nodes = " ".repeat(16 * 1024 * 1024 + 1)) }
        assertEquals(4, select().nodes.size)
    }
}
