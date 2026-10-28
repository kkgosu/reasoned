package com.kkgosu.learnharness.model

/** Renderer projection shared by authored samples and source-backed knowledge; never a mastery score. */
data class GraphNode(
    val id: String,
    val label: String,
    val statement: String,
    val reasoning: String,
    val cluster: Int,
    val implication: Boolean = false
) {
    override fun toString() = "$label. $statement"
}

data class GraphData(
    val nodes: List<GraphNode>,
    val edges: List<Pair<String, String>>,
    val clusters: List<String>
)
