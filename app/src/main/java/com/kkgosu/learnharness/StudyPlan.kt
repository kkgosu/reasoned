package com.kkgosu.learnharness

import org.json.JSONArray
import org.json.JSONObject

/** Session scaffolding only: visited/current are position signals, never mastery or graph evidence. */
internal data class StudyPlan(
    val nodes: List<Node>,
    val edges: List<Pair<String, String>>,
    val version: Int = 1
) {
    data class Node(val id: String, val title: String, val summary: String)

    fun json() = JSONObject().put("version", version).put("nodes", JSONArray(nodes.map {
        JSONObject().put("id", it.id).put("title", it.title).put("summary", it.summary)
    })).put(
        "edges",
        JSONArray(edges.map { (from, to) -> JSONObject().put("from", from).put("to", to) })
    )

    fun positions(protocol: String): Pair<String?, Set<String>> {
        val ids = nodes.map { it.id }.toSet()
        val visits = JSONArray(protocol).objects()
            .filter { it.optString("type") == "function_call" && it.optString("name") == "ask_question" }
            .mapNotNull {
                JSONObject(it.getString("arguments")).optString("nodeId").takeIf(ids::contains)
            }
            .fold(emptyList<String>()) { result, id -> if (result.lastOrNull() == id) result else result + id }
        return visits.lastOrNull() to visits.dropLast(1).toSet()
    }

    companion object {
        fun parse(value: JSONObject, version: Int = value.optInt("version", 1)): StudyPlan {
            val nodes = value.getJSONArray("nodes").objects().map {
                it.exactKeys(setOf("id", "title", "summary"))
                Node(
                    it.strictString("id", 80),
                    it.strictString("title", 160),
                    it.strictString("summary", 1200, allowEmpty = true)
                )
            }
            if (nodes.size !in 1..24 || nodes.map { it.id }.toSet().size != nodes.size ||
                nodes.any { !it.id.matches(Regex("[a-z0-9][a-z0-9_-]{0,79}")) }
            ) throw ProviderFailure("invalid_plan_nodes")
            val edges = value.getJSONArray("edges").objects().map {
                it.exactKeys(setOf("from", "to")); it.strictString(
                "from",
                80
            ) to it.strictString("to", 80)
            }
            if (edges.size > 80 || edges.toSet().size != edges.size) throw ProviderFailure("invalid_plan_edges")
            validateDag(nodes.map { it.id }.toSet(), edges)
            return StudyPlan(nodes, edges, version)
        }
    }
}

/** Also applied after identity resolution: two valid local IDs must not collapse into a self-loop. */
internal fun validateDag(ids: Set<String>, edges: List<Pair<String, String>>) {
    if (edges.any { (a, b) -> a == b || a !in ids || b !in ids }) throw ProviderFailure("invalid_graph_reference")
    val incoming = ids.associateWith { 0 }.toMutableMap()
    val outgoing = edges.groupBy({ it.first }, { it.second })
    edges.forEach { incoming[it.second] = incoming.getValue(it.second) + 1 }
    val ready = java.util.ArrayDeque(ids.filter { incoming.getValue(it) == 0 })
    var count = 0
    while (ready.isNotEmpty()) {
        val id = ready.removeFirst(); count++
        outgoing[id].orEmpty().forEach { next ->
            incoming[next] = incoming.getValue(next) - 1
            if (incoming.getValue(next) == 0) ready.add(next)
        }
    }
    if (count != ids.size) throw ProviderFailure("graph_cycle")
}

internal fun JSONObject.exactKeys(expected: Set<String>) {
    if (keys().asSequence().toSet() != expected) throw ProviderFailure("invalid_tool_arguments")
}
