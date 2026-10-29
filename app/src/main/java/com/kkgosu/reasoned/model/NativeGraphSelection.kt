package com.kkgosu.reasoned.model

import com.kkgosu.reasoned.utils.objects
import org.json.JSONObject

/**
 * Pure selection from native v1 graph snapshots, following LearnHarness 3dd52110's
 * contracts/graph.ts and core/graph.ts (Amos Blomqvist, AGPL v3).
 * Not a package parser/installer: callers still need bounded external-input validation,
 * source-file/capability checks and stopped, coherent chat/graph snapshots before transfer.
 */
internal object NativeGraphSelection {
    data class Reference(val chatId: String, val messageId: String)
    data class Selection(
        val nodes: List<String>,
        val chains: List<String>,
        val externalProvenance: Set<Reference>,
    )

    /** Raw assertion lines and their order survive; no graph merge, traversal, IO or extraction. */
    fun select(chats: List<NativeChat>, nodes: String, chains: String, format: String? = null): Selection {
        require(chats.isNotEmpty() && chats.size <= 100 && chats.map { it.id }.toSet().size == chats.size) {
            "Select distinct native chats"
        }
        require(nodes.length.toLong() + chains.length <= 16 * 1024 * 1024) { "Native graph snapshot limit" }
        if (format != null) {
            require(format.length <= 4096) { "Native graph format limit" }
            val version = JSONObject(format).get("version")
            require(version is Number && version.toDouble() == 1.0) { "Unsupported native graph version" }
        }
        val messages = chats.associate { chat ->
            chat.id to chat.snapshot().getJSONArray("messages").objects().map { it.getString("id") }.toSet()
        }
        val nodeLines = assertions(nodes, false)
        val chainLines = assertions(chains, true)
        val firstNodes = linkedMapOf<String, Assertion>()
        nodeLines.forEach { firstNodes.putIfAbsent(it.id, it) }
        val firstChains = linkedMapOf<List<String>, Assertion>()
        val chainIds = mutableMapOf<String, List<String>>()
        chainLines.forEach { line ->
            require(line.id !in firstNodes) { "Native node/chain identity collision" }
            require(line.key.all { it in firstNodes }) { "Dangling native chain endpoint" }
            require(chainIds.putIfAbsent(line.id, line.key).let { it == null || it == line.key }) {
                "Conflicting native chain identity"
            }
            firstChains.putIfAbsent(line.key, line)
        }
        fun selected(line: Assertion) = line.provenance.any { it.chatId in messages }
        val selectedChains = chainLines.filter(::selected).map { it.key }.toSet()
        // The first equivalent argument owns the chain ID, even if a later assertion selected it.
        val keptChains = chainLines.filter { selected(it) ||
            (it.key in selectedChains && firstChains[it.key] === it) }
        val nodeIds = nodeLines.filter(::selected).mapTo(mutableSetOf()) { it.id }
        keptChains.forEach { nodeIds.addAll(it.key) }
        // Keep a required endpoint's definition, not all incident chains or unrelated link-appends.
        val keptNodes = nodeLines.filter { it.id in nodeIds && (selected(it) || firstNodes[it.id] === it) }
        val provenance = (keptNodes + keptChains).flatMap { it.provenance }.toSet()
        provenance.forEach { point ->
            val localMessages = messages[point.chatId]
            require(localMessages == null || point.messageId.isEmpty() || point.messageId in localMessages) {
                "Missing selected-chat provenance message"
            }
        }
        return Selection(keptNodes.map { it.raw }, keptChains.map { it.raw },
            provenance.filterTo(linkedSetOf()) { it.chatId !in messages })
    }

    private data class Assertion(
        val raw: String,
        val id: String,
        val provenance: Set<Reference>,
        // Sorted premises followed by the conclusion: preserves native argument equivalence.
        val key: List<String>,
    )

    private fun assertions(raw: String, chain: Boolean): List<Assertion> {
        val result = mutableListOf<Assertion>()
        for (line in raw.splitToSequence('\n')) {
            if (line.isBlank()) continue
            require(result.size < 10_000 && line.length <= 256 * 1024) { "Native graph assertion limit" }
            val value = JSONObject(line)
            fun id(key: String): String {
                val text = value.get(key) as? String
                require(text != null && text.length <= 512 && text.isNotBlank()) {
                    "Invalid native graph $key"
                }
                return text
            }
            val points = value.getJSONArray("provenance")
            require(points.length() in 1..1024) { "Native provenance limit" }
            val provenance = points.objects().map { point ->
                val chatId = point.get("chatId") as? String
                val messageId = point.get("messageId") as? String
                require(chatId != null && chatId.isNotBlank() && chatId.length <= 200 &&
                    messageId != null && messageId.length <= 512) { "Invalid native provenance" }
                Reference(chatId, messageId)
            }.toSet()
            val key = if (chain) {
                val array = value.getJSONArray("premises")
                require(array.length() in 1..128) { "Native chain premise limit" }
                val premises = (0 until array.length()).map { index ->
                    val premise = array.get(index) as? String
                    require(premise != null && premise.isNotBlank() && premise.length <= 512) {
                        "Invalid native chain premise"
                    }
                    premise
                }
                require(premises.toSet().size == premises.size) { "Duplicate native chain premise" }
                premises.sorted() + id("conclusion")
            } else emptyList()
            result += Assertion(line, id("id"), provenance, key)
        }
        return result
    }
}
