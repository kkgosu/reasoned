package com.kkgosu.reasoned.model

import org.json.JSONArray
import org.json.JSONObject

internal data class KnowledgeSource(
    val session: String,
    val message: Long,
    val title: String,
    val text: String
)

internal data class KnowledgeNode(
    val id: String,
    val name: String,
    val statement: String,
    val aliases: List<String>,
    val birth: String
)

internal data class KnowledgeChain(
    val id: String,
    val premises: List<String>,
    val conclusion: String
)

internal data class KnowledgeSnapshot(
    val nodes: List<KnowledgeNode>,
    val chains: List<KnowledgeChain>,
    val sources: Map<String, List<KnowledgeSource>>,
    val titles: Map<String, String> = emptyMap()
) {
    fun data(): GraphData {
        val births = nodes.map { it.birth }.distinct()
        val clusters = births.map { birth ->
            titles[birth] ?: sources.values.flatten().firstOrNull { it.session == birth }?.title
            ?: birth
        }
        val byId = nodes.associateBy { it.id }
        val propositions = nodes.map { node ->
            GraphNode(
                node.id, node.name, node.statement,
                chains.filter { it.conclusion == node.id }.joinToString("\n\n") { chain ->
                    chain.premises.joinToString(
                        "\n",
                        "Premises used together:\n",
                        "\nTherefore: ${node.statement}"
                    ) { "• ${byId.getValue(it).statement}" }
                }, births.indexOf(node.birth)
            )
        }
        val arguments = chains.filter { it.premises.size > 1 }.map { chain ->
            GraphNode(
                chain.id, "∴", "Reasoning chain",
                chain.premises.joinToString(
                    "\n",
                    "Premises used together:\n",
                    "\nTherefore: ${byId.getValue(chain.conclusion).statement}"
                ) { "• ${byId.getValue(it).statement}" },
                births.indexOf(byId.getValue(chain.conclusion).birth), implication = true
            )
        }
        return GraphData(
            propositions + arguments,
            chains.flatMap { chain ->
                if (chain.premises.size == 1) listOf(chain.premises.single() to chain.conclusion)
                else chain.premises.map { it to chain.id } + (chain.id to chain.conclusion)
            },
            clusters
        )
    }

    fun nodeJson(node: KnowledgeNode) =
        JSONObject().put("id", node.id).put("name", node.name).put("statement", node.statement)
            .put("aliases", JSONArray(node.aliases)).put("session", node.birth)
}
