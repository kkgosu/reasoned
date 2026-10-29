package com.kkgosu.reasoned.model

import com.kkgosu.reasoned.utils.objects
import org.json.JSONArray
import org.json.JSONObject

/** LearnHarness 3dd52110 mermaid-plan.ts (AGPL-3.0) authoring subset, not a renderer.
 * Reuses Android's bounded DAG validation; unsupported input never enters a WebView.
 */
internal object NativePlan {
    fun display(tree: JSONObject): StudyPlan = StudyPlan.parse(JSONObject().put("nodes", JSONArray(
        tree.getJSONArray("nodes").objects().map {
            JSONObject().put("id", it.getString("id")).put("title", it.getString("title")).put("summary", "")
        })).put("edges", tree.getJSONArray("edges")).put("version", tree.optInt("version", 1)))

    fun parse(mermaid: String): JSONObject {
        require(mermaid.length in 1..16_000) { "Plan must contain 1–16000 characters" }
        val lines = mermaid.split(Regex("\\r?\\n"))
        val first = lines.indexOfFirst { it.nativeTrim().isNotEmpty() && !it.nativeTrim().startsWith("%%") }
        require(first >= 0 && Regex("(graph|flowchart)\\s+(td|tb|lr|rl|bt)\\s*", RegexOption.IGNORE_CASE)
            .matches(lines[first].nativeTrim())) { "Expected a graph TD or flowchart direction header" }
        val nodes = linkedMapOf<String, String>()
        val edges = linkedSetOf<Pair<String, String>>()
        val endpoint = Regex("^([^\\s()\\[\\]]+)\\s*(?:\\[([^\\[\\]]*)\\]|\\(\\s*\"([^\"]*)\"\\s*\\)|\\(([^()]*)\\))?$")
        for (i in first + 1 until lines.size) {
            val line = lines[i].nativeTrim()
            if (line.isEmpty() || line.startsWith("%%")) continue
            val ids = line.split(Regex("-->|==>|-\\.->")).map { segment ->
                val match = endpoint.matchEntire(segment.nativeTrim())
                require(match != null) { "line ${i + 1}: expected slug[title] or a --> b" }
                val id = match.groupValues[1]
                require(id.matches(Regex("[a-z0-9]+([_-][a-z0-9]+)*"))) { "line ${i + 1}: ids must be lowercase slugs" }
                var label = match.groups[2]?.value ?: match.groups[3]?.value ?: match.groups[4]?.value ?: ""
                label = label.nativeTrim()
                if (label.length >= 2 && ((label.startsWith('"') && label.endsWith('"')) ||
                            (label.startsWith('\'') && label.endsWith('\'')))) label = label.substring(1, label.lastIndex).nativeTrim()
                if (id !in nodes || (label.isNotEmpty() && nodes[id] == id)) nodes[id] = label.ifEmpty { id }
                id
            }
            ids.zipWithNext().forEach { (from, to) ->
                require(from != to) { "line ${i + 1}: a node cannot require itself" }
                edges += from to to
            }
        }
        val tree = JSONObject().put("version", 1)
            .put("nodes", JSONArray(nodes.map { (id, title) -> JSONObject().put("id", id).put("title", title) }))
            .put("edges", JSONArray(edges.map { (from, to) -> JSONObject().put("from", from).put("to", to) }))
        display(tree) // Existing node/edge/size/cycle checks, not a second graph validator.
        return tree
    }
}
